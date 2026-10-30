import cowboy from '@neko-static/sprites/actions/cowboy/manifest.json';
import wizard from '@neko-static/sprites/actions/wizard/manifest.json';
import godzilla from '@neko-static/sprites/actions/godzilla/manifest.json';
import ronova from '@neko-static/sprites/actions/ronova/manifest.json';
import zenitsu from '@neko-static/sprites/actions/zenitsu/manifest.json';
import tanjiro from '@neko-static/sprites/actions/tanjiro/manifest.json';
import { prepareImage, spriteImage } from './ImageAssets';
import type { CatPlayer } from '../types/game';

export type CatAction = 'idle' | 'run' | 'catch_success' | 'catch_wrong' | 'win' | 'lose';
export interface SourceFrame {
  x: number; y: number; width: number; height: number; anchorX: number; anchorY: number;
  scaleHeight?: number;
}
interface ActionConfig {
  frameCount: number; columns: number; frameDurationMs: number; loop: boolean; path: string;
  sourceFrames?: SourceFrame[];
  frameDurationsMs?: number[];
}
interface ActionBundle {
  cellSize: number; baseline: number; portrait?: string; lobby?: ActionConfig;
  actions: Record<CatAction, ActionConfig>;
}
const bundles: Record<number, ActionBundle> = { 1: cowboy, 2: wizard, 3: godzilla, 4: ronova, 5: zenitsu, 6: tanjiro };
export const CAT_ACTIONS: CatAction[] = ['idle', 'run', 'catch_success', 'catch_wrong', 'win', 'lose'];

/**
 * Gói skin admin tải lên (render code PACK): manifest.json cùng định dạng skin gốc, đường dẫn ảnh tương đối trong gói.
 * Đổi sang đường dẫn tuyệt đối dưới thư mục gói rồi đăng ký như một nhân vật có sẵn.
 */
export function registerCatPack(skin: number, packPath: string, manifest: ActionBundle) {
  const base = packPath.endsWith('/') ? packPath : `${packPath}/`;
  const absolute = (path: string) => base + path.replace(/^\/+/, '');
  const rebase = (config: ActionConfig): ActionConfig => ({ ...config, path: absolute(config.path) });
  const actions = {} as Record<CatAction, ActionConfig>;
  for (const action of CAT_ACTIONS) {
    if (!manifest.actions?.[action]) throw new Error(`Gói skin thiếu hành động ${action}`);
    actions[action] = rebase(manifest.actions[action]);
  }
  bundles[skin] = {
    cellSize: manifest.cellSize, baseline: manifest.baseline, actions,
    portrait: manifest.portrait ? absolute(manifest.portrait) : undefined,
    lobby: manifest.lobby ? rebase(manifest.lobby) : undefined,
  };
}

const packLoads = new Map<number, Promise<void>>();
/** Tải manifest của gói một lần; lỗi thì nhân vật dùng mèo cam thay vì làm hỏng trận. */
export function loadCatPack(skin: number, packPath: string): Promise<void> {
  let pending = packLoads.get(skin);
  if (!pending) {
    const base = packPath.endsWith('/') ? packPath : `${packPath}/`;
    pending = fetch(`${base}manifest.json`)
      .then(response => { if (!response.ok) throw new Error('Không tải được gói skin'); return response.json(); })
      .then(manifest => registerCatPack(skin, base, manifest as ActionBundle))
      .catch(() => { packLoads.delete(skin); });
    packLoads.set(skin, pending);
  }
  return pending;
}
export function catPackLoading(skin: number): Promise<void> | undefined {
  return packLoads.get(skin);
}
const clocks = new WeakMap<CatPlayer, { key: string; start: number; last: number }>();
const actionFor = (state: CatPlayer['state']): CatAction => state === 'run_left' || state === 'run_right' ? 'run' : state;

function loadImage(path: string): HTMLImageElement | undefined {
  if (typeof Image === 'undefined') return;
  return spriteImage(path);
}
export function hasCatActions(skin: number): boolean {
  return Object.prototype.hasOwnProperty.call(bundles, skin);
}
/** Fetch only the equipped character, rather than every skin in the shop. */
export function preloadCatActions(skin: number) {
  const bundle = bundles[skin];
  if (!bundle) return;
  if (bundle.portrait) loadImage(bundle.portrait);
  Object.values(bundle.actions).forEach(action => loadImage(action.path));
}
export async function prepareCatActions(skin: number) {
  const bundle = bundles[skin];
  if (!bundle) return;
  const paths = Object.values(bundle.actions).map(action => action.path);
  if (bundle.portrait) paths.push(bundle.portrait);
  await Promise.all([...new Set(paths)].map(prepareImage));
}
export function catActionsReady(skin: number, state: CatPlayer['state'] = 'idle'): boolean {
  const bundle = bundles[skin];
  if (!bundle) return false;
  const image = loadImage(bundle.actions[actionFor(state)].path);
  return !!image && image.complete && image.naturalWidth > 0;
}
/** Preserve character identity while its animation sheet downloads. */
export function drawCharacterPortrait(ctx: CanvasRenderingContext2D, cat: CatPlayer, skin: number): boolean {
  const path = bundles[skin]?.portrait;
  const image = path ? loadImage(path) : undefined;
  if (!image?.complete || !image.naturalWidth) return false;
  const height = cat.height * 1.7, width = height * image.naturalWidth / image.naturalHeight;
  ctx.save(); ctx.translate(cat.x, cat.y);
  if (cat.state === 'run_left') ctx.scale(-1, 1);
  ctx.drawImage(image, -width / 2, cat.height * 0.42 - height * 0.96, width, height);
  ctx.restore(); return true;
}
export function sourceFrame(bundle: ActionBundle, config: ActionConfig, frame: number): SourceFrame {
  return config.sourceFrames?.[frame] ?? {
    x: (frame % config.columns) * bundle.cellSize, y: Math.floor(frame / config.columns) * bundle.cellSize,
    width: bundle.cellSize, height: bundle.cellSize, anchorX: bundle.cellSize / 2, anchorY: bundle.baseline,
  };
}

/** Dedicated resting poses for human/kaiju characters in the pre-game lobby. */
export function drawCharacterLobby(ctx: CanvasRenderingContext2D, width: number, height: number,
  skin: number, time: number, reducedMotion = false): boolean {
  const bundle = bundles[skin], config = bundle?.lobby;
  if (!config) return false;
  const image = loadImage(config.path);
  if (!image?.complete || !image.naturalWidth) return false;
  const durations = config.frameDurationsMs ?? Array(config.frameCount).fill(config.frameDurationMs);
  let elapsed = reducedMotion ? 0 : time % durations.reduce((sum: number, value: number) => sum + value, 0);
  let frame = 0;
  while (frame < config.frameCount - 1 && elapsed >= durations[frame]) elapsed -= durations[frame++];
  const source = sourceFrame(bundle, config, frame), scale = height * 0.9 / (source.scaleHeight ?? source.height);
  ctx.clearRect(0, 0, width, height);
  ctx.drawImage(image, source.x, source.y, source.width, source.height,
    width / 2 - source.anchorX * scale, height * 0.96 - source.anchorY * scale,
    source.width * scale, source.height * scale);
  return true;
}
/** Movement loops; feedback starts at frame zero and holds its last pose. */
export function drawCatAction(ctx: CanvasRenderingContext2D, cat: CatPlayer, time: number, skin: number, previewLoop = false) {
  const bundle = bundles[skin]; if (!bundle) return;
  const action = actionFor(cat.state), config = bundle.actions[action], image = loadImage(config.path);
  const key = `${skin}:${action}`;
  let clock = clocks.get(cat);
  if (!clock || clock.key !== key || time < clock.last) {
    clock = { key, start: time, last: time }; clocks.set(cat, clock);
  }
  clock.last = time;
  const elapsed = time - clock.start, step = Math.floor(elapsed / config.frameDurationMs);
  const frame = config.loop || previewLoop ? step % config.frameCount : Math.min(step, config.frameCount - 1);
  const source = sourceFrame(bundle, config, frame), size = cat.height * 1.7, scale = size / (source.scaleHeight ?? source.height);
  const feet = cat.height * 0.42, hop = action === 'win' ? Math.max(0, Math.sin(elapsed * Math.PI / 450)) * 13 : 0;
  const breathe = skin >= 3 && action === 'idle' ? Math.sin(elapsed / 450) * 1.5 : 0;
  ctx.save(); ctx.translate(cat.x, cat.y);
  ctx.fillStyle = 'rgba(0,0,0,0.18)'; ctx.beginPath(); ctx.ellipse(0, feet, cat.width * 0.38, 7, 0, 0, Math.PI * 2); ctx.fill();
  if (cat.shieldActive) {
    ctx.strokeStyle = '#34d399'; ctx.lineWidth = 2; ctx.beginPath();
    ctx.ellipse(0, -cat.height * 0.15, cat.width * 0.7, cat.height * 0.85, 0, 0, Math.PI * 2); ctx.stroke();
  }
  if (cat.state === 'run_left') ctx.scale(-1, 1);
  if (image?.complete && image.naturalWidth > 0) {
    ctx.drawImage(image, source.x, source.y, source.width, source.height,
      -source.anchorX * scale, feet - source.anchorY * scale - hop - breathe, source.width * scale, source.height * scale);
  }
  ctx.restore();
}
