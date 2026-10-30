import { Container, Graphics, Sprite, Texture } from 'pixi.js';

/**
 * Super-cannon charge for every plane: energy spirals into the muzzle, a two-layer
 * plasma orb swells, arcs jump to the hull, the orb compresses just before firing, then a shockwave on release.
 * Render-only: the match store owns the phase and its timing (charge lasts CHARGE_MS).
 */
export interface ChargeColors { primary: number; accent: number; hot: number }
export interface ChargeState {
  phase: string; elapsed: number; x: number; y: number; now: number;
  motion: boolean; particles: 'high' | 'low' | 'off'; colors: ChargeColors;
}

const CHARGE_MS = 900;
const BURST_MS = 420;
const TAU = Math.PI * 2;
const clamp = (v: number) => Math.max(0, Math.min(1, v));
const seg = (v: number, a: number, b: number) => clamp((v - a) / (b - a));
const outCubic = (p: number) => 1 - (1 - p) ** 3;

function rand(i: number, k = 0) {
  let x = Math.imul(i + 1, 374761393) ^ Math.imul(k + 7, 668265263);
  x = Math.imul(x ^ (x >>> 13), 1274126177);
  return ((x ^ (x >>> 16)) >>> 0) / 4294967296;
}

function canvasTexture(size: number, draw: (g: CanvasRenderingContext2D, c: number) => void) {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = size;
  draw(canvas.getContext('2d')!, size / 2);
  return Texture.from(canvas);
}

let shared: { glow: Texture; swirl: Texture } | null = null;
function textures() {
  if (shared) return shared;
  const glow = canvasTexture(128, (g, c) => {
    const grad = g.createRadialGradient(c, c, 0, c, c, c);
    grad.addColorStop(0, 'rgba(255,255,255,1)');
    grad.addColorStop(0.25, 'rgba(255,255,255,0.55)');
    grad.addColorStop(0.6, 'rgba(255,255,255,0.12)');
    grad.addColorStop(1, 'rgba(255,255,255,0)');
    g.fillStyle = grad;
    g.fillRect(0, 0, c * 2, c * 2);
  });
  // Three spiral arms of soft plasma; rotating two tinted copies against each other reads as a churning orb.
  const swirl = canvasTexture(256, (g, c) => {
    for (let arm = 0; arm < 3; arm++) {
      for (let i = 0; i < 240; i++) {
        const u = i / 240;
        const a = arm * TAU / 3 + u * 4.4 + (rand(i, arm) - 0.5) * 0.35;
        const r = 14 + u * 98 + (rand(i, arm + 5) - 0.5) * 10;
        const x = c + Math.cos(a) * r, y = c + Math.sin(a) * r;
        const size = 5 + 11 * (1 - u) * rand(i, arm + 9);
        const dot = g.createRadialGradient(x, y, 0, x, y, size);
        dot.addColorStop(0, `rgba(255,255,255,${0.1 + 0.2 * (1 - u)})`);
        dot.addColorStop(1, 'rgba(255,255,255,0)');
        g.fillStyle = dot;
        g.fillRect(x - size, y - size, size * 2, size * 2);
      }
    }
    g.strokeStyle = 'rgba(255,255,255,0.35)';
    g.lineWidth = 3;
    g.beginPath();
    g.arc(c, c, 62, 0.3, 4.6);
    g.stroke();
  });
  shared = { glow, swirl };
  return shared;
}

function glowSprite(texture: Texture) {
  const s = new Sprite(texture);
  s.anchor.set(0.5);
  s.blendMode = 'add';
  s.visible = false;
  return s;
}

export class ChargeFx {
  private readonly root = new Container();
  private readonly back = new Container();
  private readonly underglow: Sprite;
  private readonly halo: Sprite;
  private readonly swirlA: Sprite;
  private readonly swirlB: Sprite;
  private readonly core: Sprite;
  private readonly lines = new Graphics();
  private lastPhase = 'idle';
  private burstAt = -1;

  /** `behind` sits under the ship (the light it casts), `front` above it (the orb at the muzzle). */
  constructor(behind: Container, front: Container) {
    const { glow, swirl } = textures();
    this.underglow = glowSprite(glow);
    this.halo = glowSprite(glow);
    this.swirlA = glowSprite(swirl);
    this.swirlB = glowSprite(swirl);
    this.core = glowSprite(glow);
    this.lines.blendMode = 'add';
    this.back.addChild(this.underglow);
    this.root.addChild(this.halo, this.lines, this.swirlA, this.swirlB, this.core);
    behind.addChild(this.back);
    front.addChild(this.root);
  }

  update(s: ChargeState) {
    if (this.lastPhase === 'charge' && s.phase === 'firing') this.burstAt = s.now;
    this.lastPhase = s.phase;
    const g = this.lines;
    g.clear();
    const charging = s.phase === 'charge';
    const burst = this.burstAt >= 0 ? (s.now - this.burstAt) / BURST_MS : 1;
    for (const sprite of [this.underglow, this.halo, this.swirlA, this.swirlB, this.core]) sprite.visible = charging || burst < 1;
    if (charging) this.drawCharge(s);
    else if (burst < 1) this.drawBurst(s, clamp(burst));
  }

  private place(sprite: Sprite, x: number, y: number, diameter: number, tint: number, alpha: number, rotation = 0) {
    sprite.position.set(x, y);
    sprite.scale.set(Math.max(0.001, diameter / sprite.texture.width));
    sprite.tint = tint;
    sprite.alpha = clamp(alpha);
    sprite.rotation = rotation;
  }

  private drawCharge(s: ChargeState) {
    const { primary, accent, hot } = s.colors;
    const g = this.lines;
    const p = s.motion ? clamp(s.elapsed / CHARGE_MS) : 0.8;
    const t = s.motion ? s.now : 0;
    const squeeze = s.motion ? seg(p, 0.86, 1) : 0;
    const r = (5 + 21 * p * p) * (1 - 0.45 * squeeze) * (1 + 0.08 * Math.sin(t * 0.03) * p);
    const jx = s.motion ? Math.sin(t * 0.09) * 1.6 * p * p : 0;
    const jy = s.motion ? Math.cos(t * 0.11) * 1.6 * p * p : 0;
    const x = s.x + jx, y = s.y + jy;
    this.place(this.underglow, s.x, s.y + 44, 150 + 120 * p, primary, 0.12 + 0.36 * p);
    this.place(this.halo, x, y, r * 8.5, primary, 0.35 + 0.45 * p);
    this.place(this.swirlA, x, y, r * 3.6, primary, 0.55 + 0.4 * p, t * 0.004);
    this.place(this.swirlB, x, y, r * 2.9, accent, 0.45 + 0.4 * p, -t * 0.006 + 1.3);
    this.place(this.core, x, y, r * 1.9, 0xffffff, 0.95);
    if (s.particles === 'off') return;
    const high = s.particles === 'high';

    // Energy streaks spiralling into the muzzle.
    const count = high ? 54 : 22;
    for (let i = 0; i < count; i++) {
      const phase = (s.elapsed / 260) + rand(i, 1);
      const cycle = Math.floor(phase);
      const q = s.motion ? phase - cycle : rand(i, 2);
      const a = rand(i, cycle + 3) * TAU + q * 1.3;
      const d = (58 + 96 * rand(i, cycle + 11)) * (1 - q * q) + r * 0.6;
      const px = x + Math.cos(a) * d, py = y + Math.sin(a) * d * 0.82;
      const tail = 7 + 24 * q;
      g.moveTo(px + Math.cos(a) * tail, py + Math.sin(a) * tail * 0.82).lineTo(px, py)
        .stroke({ color: i % 3 ? primary : hot, width: 1.2 + 1.2 * rand(i, 5), alpha: (0.2 + 0.75 * q) * (1 - 0.85 * squeeze), cap: 'round' });
    }

    // Arcs jumping from the charge to the hull, re-drawn ~18 times a second.
    const frame = Math.floor(t / 55);
    const arcs = 1 + Math.floor(p * (high ? 4 : 2));
    for (let k = 0; k < arcs; k++) {
      const a = Math.PI * (0.15 + 0.7 * rand(frame, k));
      const reach = 38 + 26 * rand(frame, k + 20);
      const ex = s.x + Math.cos(a) * reach * (rand(frame, k + 40) < 0.5 ? -1 : 1), ey = s.y + 30 + Math.sin(a) * reach * 0.6;
      const pts: number[] = [x, y];
      const nx = -(ey - y), ny = ex - x;
      const len = Math.hypot(nx, ny) || 1;
      for (let j = 1; j < 6; j++) {
        const f = j / 6;
        const off = (rand(frame * 7 + k, j) - 0.5) * 16 * Math.sin(f * Math.PI);
        pts.push(x + (ex - x) * f + nx / len * off, y + (ey - y) * f + ny / len * off);
      }
      pts.push(ex, ey);
      for (const [width, color, alpha] of [[4, primary, 0.28], [1.1, 0xffffff, 0.9]] as const) {
        g.moveTo(pts[0], pts[1]);
        for (let j = 2; j < pts.length; j += 2) g.lineTo(pts[j], pts[j + 1]);
        g.stroke({ color, width, alpha: alpha * (0.4 + 0.6 * p), cap: 'round', join: 'round' });
      }
    }

    // Faint anime focus lines closing in on the muzzle.
    if (high) {
      for (let i = 0; i < 22; i++) {
        const a = rand(i, frame) * TAU;
        const inner = 58 + 30 * rand(i, frame + 1);
        g.moveTo(x + Math.cos(a) * inner, y + Math.sin(a) * inner).lineTo(x + Math.cos(a) * 165, y + Math.sin(a) * 165)
          .stroke({ color: hot, width: 1, alpha: (0.03 + 0.1 * p) * (0.5 + rand(i, frame + 2)) });
      }
    }
  }

  private drawBurst(s: ChargeState, b: number) {
    const { primary, accent, hot } = s.colors;
    const g = this.lines;
    const fade = 1 - b;
    this.place(this.underglow, s.x, s.y + 44, 300, primary, 0.5 * fade);
    this.place(this.halo, s.x, s.y, 120 + 160 * b, primary, 0.8 * fade);
    this.place(this.swirlA, s.x, s.y, 70 + 90 * b, primary, 0.7 * fade, s.now * 0.004);
    this.place(this.swirlB, s.x, s.y, 60 + 70 * b, accent, 0.6 * fade, -s.now * 0.006);
    this.place(this.core, s.x, s.y, 30 * fade + 8, 0xffffff, fade);
    // A soft shockwave: a wide tinted band under a thin hot edge, both thinning as they expand.
    const ring = 16 + 140 * outCubic(b);
    g.circle(s.x, s.y, ring).stroke({ color: primary, width: 2 + 9 * fade, alpha: 0.35 * fade });
    g.circle(s.x, s.y, ring).stroke({ color: hot, width: 0.8 + 1.4 * fade, alpha: 0.85 * fade });
    g.circle(s.x, s.y, 10 + 80 * outCubic(b)).stroke({ color: accent, width: 1.2, alpha: 0.5 * fade });
    if (s.particles === 'off') return;
    for (let i = 0; i < 18; i++) {
      const a = rand(i, 61) * TAU;
      const d = (30 + 110 * rand(i, 62)) * outCubic(b);
      const tail = 14 * fade + 3;
      g.moveTo(s.x + Math.cos(a) * d, s.y + Math.sin(a) * d)
        .lineTo(s.x + Math.cos(a) * (d + tail), s.y + Math.sin(a) * (d + tail))
        .stroke({ color: i % 2 ? hot : primary, width: 2, alpha: fade, cap: 'round' });
    }
  }

  destroy() {
    this.back.destroy({ children: true });
    this.root.destroy({ children: true });
  }
}
