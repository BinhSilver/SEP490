import { assetUrl } from '../assets';
import { CatPlayer } from '../types/game';
import { catActionsReady, drawCatAction, drawCharacterPortrait, hasCatActions, preloadCatActions } from './CatActionAtlas';
import { SpriteRenderer } from './SpriteRenderer';

export type CatSkinId = 0 | 1 | 2 | 3 | 4 | 5 | 6;

export interface CatSkinInfo {
  id: CatSkinId;
  name: string;
  desc: string;
  themeColor: string;
}

/** Skin 0 uses the original sprite sheet; skins 1 and 2 use the per-action atlases (CatActionAtlas). */
export const CAT_SKINS: CatSkinInfo[] = [
  {
    id: 0,
    name: 'Mèo cam',
    desc: 'Chú mèo cam hoạt hình dễ thương phong cách anime gốc.',
    themeColor: '#FF9F43',
  },
  {
    id: 1,
    name: 'Mèo cao bồi',
    desc: 'Chạy ôm giỏ, hứng đúng, hứng sai, ăn mừng và có cảnh thắng riêng.',
    themeColor: '#D97706',
  },
  {
    id: 2,
    name: 'Mèo phù thủy',
    desc: 'Chạy ôm nồi phép, hứng đúng, hứng sai, ăn mừng và nằm buồn khi thử lại.',
    themeColor: '#8B5CF6',
  },
  { id: 3, name: 'Godzilla', desc: 'Godzilla chibi ôm giỏ bắt cá.', themeColor: '#38BDF8' },
  { id: 4, name: 'Ronova', desc: 'Ronova với mái tóc trắng và trang phục đỏ đen.', themeColor: '#BE123C' },
  { id: 5, name: 'Zenitsu', desc: 'Zenitsu trong áo haori vàng họa tiết tam giác.', themeColor: '#F59E0B' },
  { id: 6, name: 'Tanjiro', desc: 'Tanjiro trong áo haori caro xanh đen.', themeColor: '#10B981' },
];

const ORIGINAL_SHEET_PATH = '/sprites/cat_spritesheet.png';

/** Mã món trong Cửa hàng (data-skin của trang host) -> trang phục; không rõ thì dùng mèo cam. */
/** Trang phục -> mã món trong Cửa hàng (ngược với skinFromCode). */
export const SKIN_CODES: Record<CatSkinId, string> = {
  0: 'cat-classic', 1: 'cat-cowboy', 2: 'cat-wizard',
  3: 'cat-godzilla', 4: 'cat-ronova', 5: 'cat-zenitsu', 6: 'cat-tanjiro',
};

export function skinFromCode(code: string | null | undefined): CatSkinId {
  return CAT_SKINS.find(skin => SKIN_CODES[skin.id] === code)?.id ?? 0;
}

interface SpriteFrame {
  x: number;
  y: number;
  w: number;
  h: number;
}

interface AnimConfig {
  duration: number;
  frames: SpriteFrame[];
}

// Original sheet: 1448×1086, six rows; most rows have six columns, the run row is a seven-frame strip.
const makeCustomRowFrames = (row: number, cols = 6, totalW = 1448, totalH = 1086): SpriteFrame[] => {
  const cellW = totalW / cols;
  const cellH = totalH / 6;
  const frames: SpriteFrame[] = [];
  for (let c = 0; c < cols; c++) {
    frames.push({ x: c * cellW, y: row * cellH, w: cellW, h: cellH });
  }
  return frames;
};

const BASE_FRAME_TABLE: Record<string, AnimConfig> = {
  idle: { duration: 180, frames: makeCustomRowFrames(0, 6) },
  run: { duration: 85, frames: makeCustomRowFrames(1, 7) },
  catch_success: { duration: 100, frames: makeCustomRowFrames(2, 6) },
  catch_wrong: { duration: 120, frames: makeCustomRowFrames(3, 6) },
  lose: { duration: 360, frames: makeCustomRowFrames(5, 6) },
};

export class CatSpriteSheet {
  private static baseImage: HTMLImageElement | null = null;
  private static baseImagePath = '';
  private static baseLoaded = false;
  private static currentSkinId: CatSkinId = 0;

  public static init() {
    if (typeof window === 'undefined') return;
    preloadCatActions(this.currentSkinId);
    const sheetPath = assetUrl(ORIGINAL_SHEET_PATH);
    if (!this.baseImage || this.baseImagePath !== sheetPath) {
      this.baseLoaded = false;
      const img = new Image();
      img.onload = () => {
        if (this.baseImage === img) this.baseLoaded = true;
      };
      this.baseImage = img;
      this.baseImagePath = sheetPath;
      img.src = sheetPath;
    }
  }

  public static setSkin(skinId: CatSkinId) {
    this.currentSkinId = skinId;
    preloadCatActions(skinId);
  }

  public static getSkin(): CatSkinId {
    return this.currentSkinId;
  }

  public static draw(
    ctx: CanvasRenderingContext2D,
    cat: CatPlayer,
    time: number,
    overrideSkinId?: CatSkinId,
    previewLoop = false
  ) {
    if (!this.baseImage) this.init();

    const activeSkin = overrideSkinId ?? this.currentSkinId;
    if (hasCatActions(activeSkin)) {
      if (catActionsReady(activeSkin, cat.state)) {
        drawCatAction(ctx, cat, time, activeSkin, previewLoop);
      } else {
        if (!drawCharacterPortrait(ctx, cat, activeSkin) && activeSkin < 3) {
          SpriteRenderer.drawCat(ctx, cat, time);
        }
      }
      return;
    }

    const img = this.baseImage;
    if (!img || !this.baseLoaded) {
      SpriteRenderer.drawCat(ctx, cat, time);
      return;
    }

    const isFlipped = cat.state === 'run_left';
    ctx.save();
    ctx.translate(cat.x, cat.y);
    if (isFlipped) ctx.scale(-1, 1);

    // Bounce / wobble offsets per state.
    let bounceY: number;
    if (cat.state === 'run_left' || cat.state === 'run_right') {
      bounceY = -Math.abs(Math.sin(time * 0.02)) * 6;
    } else if (cat.state === 'catch_success') {
      bounceY = -14 + Math.sin(time * 0.02) * 4;
    } else if (cat.state === 'catch_wrong') {
      bounceY = Math.sin(time * 0.03) * 3;
    } else if (cat.state === 'win') {
      bounceY = -8 + Math.sin(time * 0.015) * 4;
    } else if (cat.state === 'lose') {
      bounceY = 4 + Math.sin(time * 0.005) * 2;
    } else {
      bounceY = Math.sin(time * 0.0035) * 2.5;
    }

    const W = cat.width;
    const H = cat.height;

    // Ground shadow
    ctx.fillStyle = `rgba(0,0,0,${Math.max(0.06, 0.22 - Math.abs(bounceY) * 0.007)})`;
    ctx.beginPath();
    ctx.ellipse(0, H * 0.42, W * 0.38, 8, 0, 0, Math.PI * 2);
    ctx.fill();

    // Shield aura
    if (cat.shieldActive) {
      ctx.strokeStyle = `rgba(52, 211, 153, ${0.5 + 0.4 * Math.sin(time * 0.008)})`;
      ctx.lineWidth = 3;
      ctx.setLineDash([6, 4]);
      ctx.beginPath();
      ctx.arc(0, bounceY - H * 0.1, W * 0.7, 0, Math.PI * 2);
      ctx.stroke();
      ctx.setLineDash([]);
    }

    const animKey = cat.state === 'run_left' || cat.state === 'run_right' ? 'run' : cat.state;
    const anim = BASE_FRAME_TABLE[animKey] || BASE_FRAME_TABLE.idle;
    const frame = anim.frames[Math.floor((time / anim.duration) % anim.frames.length)];
    const targetH = H * 1.55;
    const targetW = targetH * (frame.w / frame.h);
    ctx.drawImage(img, frame.x, frame.y, frame.w, frame.h, -targetW / 2, -targetH / 2 + bounceY - 4, targetW, targetH);

    ctx.restore();
  }
}

// Start downloading the sheets as soon as the bundle loads.
CatSpriteSheet.init();
