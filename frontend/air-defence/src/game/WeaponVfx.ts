import { Container, Graphics, Sprite, Texture } from 'pixi.js';
import { ORNATE_WEAPON_THEMES, type OrnateWeaponSkin, type OrnateWeaponTextures } from './OrnateWeaponSprites';
import type { VisualSettings } from './visualSettings';

type Point = { x: number; y: number };
type Shot = {
  root: Container; crystal: Container; trail: Graphics; points: Point[]; pointCount: number; pointHead: number;
  x: number; y: number; vx: number; vy: number;
  targetX: number; targetY: number; aimed: boolean; life: number; seed: number;
};
type Flash = { root: Container; life: number; maxLife: number; size: number; expand: boolean };
type AmbientState = { alive: boolean; charge: number; combo: number; phase: string; shield: number };

const TAU = Math.PI * 2;
const GLINT_LOCATIONS = [[-33, -6], [32, 4], [-18, 24], [18, 25], [-8, -33], [8, -28]] as const;
export const ORNATE_WEAPON_LIMITS = { shots: 32, flashes: 24, trailPoints: 12 } as const;

function sizedSprite(texture: Texture, size: number): Sprite {
  const sprite = new Sprite(texture);
  sprite.anchor.set(0.5);
  sprite.scale.set(size / Math.max(texture.width, texture.height));
  return sprite;
}

function diamond(g: Graphics, x: number, y: number, size: number, color: number, alpha: number) {
  g.poly([x, y - size, x + size * 0.45, y, x, y + size, x - size * 0.45, y])
    .fill({ color, alpha });
}

function star(g: Graphics, x: number, y: number, size: number, color: number, alpha: number) {
  g.moveTo(x - size, y).lineTo(x + size, y).stroke({ color, width: 1, alpha });
  g.moveTo(x, y - size).lineTo(x, y + size).stroke({ color, width: 1, alpha });
  diamond(g, x, y, size * 0.52, color, alpha);
}

/** A scene-owned cosmetic system: no store actions, timers, or gameplay collision. */
export class WeaponVfx {
  readonly theme;
  private readonly rear = new Container();
  private readonly front = new Container();
  private readonly effects = new Container();
  private readonly ornament = new Graphics();
  private readonly glints = new Graphics();
  private readonly nozzle = new Graphics();
  private readonly sealSprite: Sprite | null;
  private readonly chargeSprite: Sprite | null;
  private readonly beamSprite: Sprite | null;
  private readonly shots: Shot[] = [];
  private readonly flashes: Flash[] = [];
  private destroyed = false;
  private nozzleY = -46;
  private beamPhase = 'idle';
  private beamPhaseAt = 0;
  private beamStrength = 0;

  constructor(
    readonly skin: OrnateWeaponSkin,
    private readonly player: Container,
    effectsLayer: Container,
    private readonly textures: OrnateWeaponTextures | null
  ) {
    this.theme = ORNATE_WEAPON_THEMES[skin];
    this.rear.addChild(this.ornament);
    this.sealSprite = textures ? sizedSprite(textures.seal, 150) : null;
    if (this.sealSprite) { this.sealSprite.alpha = 0.2; this.rear.addChild(this.sealSprite); }
    this.rear.addChild(this.nozzle);
    this.chargeSprite = textures ? sizedSprite(textures.charge, 43) : null;
    if (this.chargeSprite) this.rear.addChild(this.chargeSprite);
    // The generated crystal carries the fine facets; native Graphics draw its sharp luminous rails above it.
    this.beamSprite = textures ? sizedSprite(textures.projectile, 210) : null;
    if (this.beamSprite) { this.beamSprite.visible = false; this.effects.addChild(this.beamSprite); }
    this.front.addChild(this.glints);
    player.addChildAt(this.rear, 0);
    player.addChild(this.front);
    effectsLayer.addChild(this.effects);
  }

  private trimFlashes(capacity: number) {
    while (this.flashes.length >= capacity) this.flashes.shift()?.root.destroy({ children: true });
  }

  private trimShots(capacity: number) {
    while (this.shots.length >= capacity) this.shots.shift()?.root.destroy({ children: true });
  }

  /** Starts on the same render tick as typing; the target is purely a visual endpoint. */
  fire(start: Point, target: Point, aimed: boolean, seed: number, prefs: VisualSettings) {
    if (this.destroyed) return;
    const capacity = prefs.particles === 'high' ? ORNATE_WEAPON_LIMITS.shots : prefs.particles === 'low' ? 16 : 12;
    this.trimShots(capacity);
    const dx = target.x - start.x;
    const dy = target.y - start.y;
    const distance = Math.hypot(dx, dy) || 1;
    const speed = 23 + seed % 3;
    const root = new Container();
    const trail = new Graphics();
    const crystal = new Container();
    const g = new Graphics();
    const { primary, secondary, core, metal } = this.theme;
    // Faceted core stays readable even if the generated sprite is unavailable.
    g.ellipse(0, 0, 7, 21).fill({ color: primary, alpha: 0.13 });
    g.poly([0, -19, 5, -5, 3, 9, 0, 15, -3, 9, -5, -5])
      .fill({ color: primary, alpha: 0.9 }).stroke({ color: secondary, width: 0.8, alpha: 0.9 });
    g.poly([0, -18, 1.6, -3, 0, 10, -1.5, -3]).fill({ color: core, alpha: 0.98 });
    g.moveTo(-5, -5).lineTo(0, -2).lineTo(5, -5).stroke({ color: metal, width: 0.7, alpha: 0.8 });
    if (this.skin === 'void-blade') {
      g.arc(0, 1, 10, Math.PI * 0.85, Math.PI * 1.85).stroke({ color: secondary, width: 1, alpha: 0.72 });
    } else if (this.skin === 'halo-fighter') {
      g.ellipse(0, 3, 10, 3).stroke({ color: secondary, width: 1.1, alpha: 0.85 });
      g.ellipse(0, 8, 7, 2).stroke({ color: metal, width: 0.65, alpha: 0.75 });
    } else if (this.skin === 'aegis-frame') {
      g.poly([-7, -1, -6, 8, 0, 13, 6, 8, 7, -1]).stroke({ color: metal, width: 1, alpha: 0.7 });
    } else {
      diamond(g, -5.5, 3, 7, primary, 0.85);
      diamond(g, 5.5, 3, 7, secondary, 0.85);
    }
    crystal.addChild(g);
    if (this.textures) {
      const sprite = sizedSprite(this.textures.projectile, 58);
      sprite.alpha = 0.9;
      crystal.addChild(sprite);
    }
    root.addChild(trail);
    root.addChild(crystal);
    this.effects.addChild(root);
    crystal.position.set(start.x, start.y);
    crystal.rotation = Math.atan2(dy, dx) + Math.PI / 2;
    this.shots.push({ root, trail, crystal,
      points: Array.from({ length: ORNATE_WEAPON_LIMITS.trailPoints }, () => ({ x: start.x, y: start.y })),
      pointCount: 1, pointHead: 1, x: start.x, y: start.y,
      vx: dx / distance * speed, vy: dy / distance * speed,
      targetX: target.x, targetY: target.y, aimed, life: 0, seed });
    this.muzzle(start.x, start.y, prefs);
  }

  private muzzle(x: number, y: number, prefs: VisualSettings) {
    const cap = prefs.particles === 'high' ? ORNATE_WEAPON_LIMITS.flashes : 10;
    this.trimFlashes(cap);
    const root = new Container();
    root.position.set(x, y);
    const g = new Graphics();
    const { primary, secondary, core } = this.theme;
    g.circle(0, 0, 8).fill({ color: primary, alpha: 0.18 });
    g.circle(0, 0, 5).stroke({ color: secondary, width: 1, alpha: 0.75 });
    star(g, 0, 0, prefs.particles === 'off' ? 4 : 10, core, 0.9);
    root.addChild(g);
    if (this.textures && prefs.particles !== 'off') {
      const sprite = sizedSprite(this.textures.charge, 36);
      sprite.alpha = 0.75;
      root.addChild(sprite);
    }
    this.effects.addChild(root);
    this.flashes.push({ root, life: 0, maxLife: prefs.reducedMotion ? 4 : 6, size: 1, expand: false });
  }

  impact(x: number, y: number, prefs: VisualSettings, strength = 1) {
    if (this.destroyed) return;
    this.trimFlashes(prefs.particles === 'high' ? ORNATE_WEAPON_LIMITS.flashes : 10);
    const root = new Container();
    root.position.set(x, y);
    const g = new Graphics();
    const { primary, secondary, core, metal } = this.theme;
    const simple = prefs.particles === 'off' || prefs.reducedMotion;
    g.circle(0, 0, 9).fill({ color: primary, alpha: 0.18 });
    star(g, 0, 0, 12, core, 0.86);
    if (!simple) {
      g.circle(0, 0, 18).stroke({ color: secondary, width: 1, alpha: 0.65 });
      const count = prefs.particles === 'high' ? 8 : 4;
      for (let i = 0; i < count; i++) {
        const angle = i * TAU / count;
        const px = Math.cos(angle) * 24;
        const py = Math.sin(angle) * 24;
        diamond(g, px, py, i % 2 ? 3 : 5, i % 2 ? metal : primary, 0.7);
        g.moveTo(px * 0.36, py * 0.36).lineTo(px * 0.7, py * 0.7)
          .stroke({ color: i % 2 ? primary : secondary, width: 0.8, alpha: 0.45 });
      }
    }
    root.addChild(g);
    if (this.textures && !simple) {
      const sprite = sizedSprite(this.textures.impact, 90);
      sprite.alpha = 0.7;
      root.addChild(sprite);
    }
    this.effects.addChild(root);
    root.scale.set(strength);
    this.flashes.push({ root, life: 0, maxLife: simple ? 9 : 16, size: strength, expand: !simple });
  }

  /** Delicate themed rails sit below enemy labels and cannot obscure the prompt. */
  drawLockOn(g: Graphics, start: Point, end: Point, now: number, prefs: VisualSettings) {
    const { primary, secondary, core } = this.theme;
    const dx = end.x - start.x;
    const dy = end.y - start.y;
    const distance = Math.hypot(dx, dy) || 1;
    const nx = -dy / distance;
    const ny = dx / distance;
    const phase = prefs.reducedMotion ? 0 : now * 0.012;
    g.moveTo(start.x, start.y).lineTo(end.x, end.y).stroke({ color: primary, width: 8, alpha: 0.09 });
    g.moveTo(start.x, start.y).lineTo(end.x, end.y).stroke({ color: core, width: 1.6, alpha: 0.9 });
    for (let rail = 0; rail < 2; rail++) {
      for (let i = 0; i <= 20; i++) {
        const p = i / 20;
        const offset = Math.sin(p * Math.PI * 6 + phase + rail * Math.PI) * 3.5 * Math.sin(p * Math.PI);
        const x = start.x + dx * p + nx * offset;
        const y = start.y + dy * p + ny * offset;
        if (i === 0) g.moveTo(x, y); else g.lineTo(x, y);
      }
      g.stroke({ color: rail === 0 ? primary : secondary, width: 0.9, alpha: 0.74 });
    }
  }

  update(now: number, delta: number, state: AmbientState, prefs: VisualSettings, nozzleY = -46) {
    if (this.destroyed) return;
    this.nozzleY = nozzleY;
    this.updateBeamSprite(now, state, prefs);
    this.drawAmbient(now, state, prefs);
    const moving = !prefs.reducedMotion;
    for (let i = this.shots.length - 1; i >= 0; i--) {
      const shot = this.shots[i];
      const remaining = Math.hypot(shot.targetX - shot.x, shot.targetY - shot.y);
      const travel = Math.hypot(shot.vx, shot.vy) * delta;
      shot.life += delta;
      if (shot.aimed && remaining <= Math.max(12, travel)) {
        this.impact(shot.targetX, shot.targetY, prefs, 0.65);
        shot.root.destroy({ children: true });
        this.shots.splice(i, 1);
        continue;
      }
      shot.x += shot.vx * delta;
      shot.y += shot.vy * delta;
      shot.crystal.position.set(shot.x, shot.y);
      if (shot.life >= 42 || shot.y < -70) {
        shot.root.destroy({ children: true });
        this.shots.splice(i, 1);
        continue;
      }
      shot.trail.clear();
      if (!moving || prefs.particles === 'off') { shot.pointCount = 0; shot.pointHead = 0; continue; }
      const pointLimit = prefs.particles === 'high' ? ORNATE_WEAPON_LIMITS.trailPoints : 5;
      const point = shot.points[shot.pointHead];
      point.x = shot.x;
      point.y = shot.y;
      shot.pointHead = (shot.pointHead + 1) % ORNATE_WEAPON_LIMITS.trailPoints;
      shot.pointCount = Math.min(pointLimit, shot.pointCount + 1);
      this.drawTrail(shot, now, prefs);
    }
    for (let i = this.flashes.length - 1; i >= 0; i--) {
      const flash = this.flashes[i];
      flash.life += delta;
      const progress = Math.min(1, flash.life / flash.maxLife);
      flash.root.alpha = (1 - progress) * (1 - progress);
      flash.root.scale.set(flash.size * (flash.expand && moving ? 0.75 + progress * 0.8 : 1));
      if (progress >= 1) {
        flash.root.destroy({ children: true });
        this.flashes.splice(i, 1);
      }
    }
  }

  private updateBeamSprite(now: number, state: AmbientState, prefs: VisualSettings) {
    if (state.phase !== this.beamPhase) {
      this.beamPhase = state.phase;
      this.beamPhaseAt = now;
    }
    const charging = state.phase === 'charge';
    const firing = state.phase === 'firing';
    this.beamStrength = prefs.reducedMotion ? 1 : Math.min(1, Math.max(0, (now - this.beamPhaseAt) / (charging ? 900 : 120)));
    const beam = this.beamSprite;
    if (!beam) return;
    beam.visible = state.alive && (charging || firing);
    if (!beam.visible) return;
    const nozzleY = this.player.y + this.nozzleY;
    const totalHeight = Math.max(1, nozzleY + 24);
    const growth = charging ? 0.16 + this.beamStrength * 0.84 : 1;
    const height = totalHeight * growth;
    const restrained = prefs.particles === 'off' || prefs.reducedMotion;
    const width = restrained ? 154 : prefs.particles === 'low' ? 190 : 210;
    // Each source quadrant is measured by Sprite itself, so any generated atlas resolution fits.
    beam.width = width * (charging ? 0.62 + this.beamStrength * 0.38 : 1);
    beam.height = height;
    beam.position.set(this.player.x, nozzleY - height / 2);
    beam.rotation = 0;
    beam.alpha = charging ? 0.035 + this.beamStrength * (restrained ? 0.08 : 0.15)
      : (restrained ? 0.45 : prefs.particles === 'low' ? 0.62 : 0.74) * this.beamStrength;
  }

  private drawTrail(shot: Shot, now: number, prefs: VisualSettings) {
    const g = shot.trail;
    const { primary, secondary, core } = this.theme;
    const count = shot.pointCount;
    const first = (shot.pointHead - count + ORNATE_WEAPON_LIMITS.trailPoints) % ORNATE_WEAPON_LIMITS.trailPoints;
    const speed = Math.hypot(shot.vx, shot.vy) || 1;
    const nx = -shot.vy / speed;
    const ny = shot.vx / speed;
    for (let i = 1; i < count; i++) {
      const previous = shot.points[(first + i - 1) % ORNATE_WEAPON_LIMITS.trailPoints];
      const point = shot.points[(first + i) % ORNATE_WEAPON_LIMITS.trailPoints];
      const alpha = i / count * 0.55;
      g.moveTo(previous.x, previous.y).lineTo(point.x, point.y)
        .stroke({ color: primary, width: 4 * i / count, alpha: alpha * 0.22 });
      g.moveTo(previous.x, previous.y).lineTo(point.x, point.y)
        .stroke({ color: core, width: 0.7, alpha: alpha * 0.48 });
      for (let strand = 0; strand < 2; strand++) {
        const phase = shot.seed + strand * Math.PI + now * 0.013;
        const a = Math.sin(i * 0.85 + phase) * 3.5;
        const b = Math.sin((i - 1) * 0.85 + phase) * 3.5;
        g.moveTo(previous.x + nx * b, previous.y + ny * b)
          .lineTo(point.x + nx * a, point.y + ny * a)
          .stroke({ color: strand ? secondary : primary, width: 0.9, alpha });
      }
      if (prefs.particles === 'high' && i % 3 === shot.seed % 3) {
        const offset = Math.sin(i + shot.seed) * 7;
        star(g, point.x + nx * offset, point.y + ny * offset, 1.8, secondary, alpha);
      }
    }
  }

  private drawAmbient(now: number, state: AmbientState, prefs: VisualSettings) {
    const { primary, secondary, core, metal } = this.theme;
    const g = this.ornament;
    const nozzle = this.nozzle;
    const moving = !prefs.reducedMotion;
    const time = moving ? now * 0.001 : 0;
    const charge = Math.min(1, Math.max(0, state.charge / 100));
    const charging = state.phase === 'charge' || state.phase === 'firing';
    const firing = state.phase === 'firing';
    const awakened = charge >= 1 || state.combo >= 5 || state.shield > 0;
    const intensity = charging ? 0.7 : awakened ? 0.55 : 0.18;
    const pulse = moving ? 0.94 + Math.sin(time * 2.1) * 0.06 : 1;
    this.rear.visible = state.alive;
    this.front.visible = state.alive;
    g.clear();
    nozzle.clear();
    this.glints.clear();
    if (!state.alive) return;

    if (this.sealSprite) {
      this.sealSprite.alpha = (firing ? 0.34 : charging ? 0.18 + this.beamStrength * 0.12 : intensity * 0.52) * pulse;
      this.sealSprite.rotation = moving ? time * 0.028 : 0;
    }
    if (this.chargeSprite) {
      this.chargeSprite.position.set(0, this.nozzleY);
      const restrained = prefs.particles === 'off' || !moving;
      const size = firing ? restrained ? 78 : prefs.particles === 'low' ? 96 : 108
        : charging ? 58 + this.beamStrength * (restrained ? 20 : 34) : 43;
      this.chargeSprite.scale.set(size / Math.max(this.chargeSprite.texture.width, this.chargeSprite.texture.height));
      this.chargeSprite.alpha = (firing ? restrained ? 0.34 : 0.54
        : charging ? 0.18 + this.beamStrength * (restrained ? 0.12 : 0.25) : 0.1 + charge * 0.23) * pulse;
      this.chargeSprite.visible = prefs.particles !== 'off' || charging;
    }
    // Fine concentric etching is drawn behind the unchanged hull.
    g.circle(0, 0, 54).stroke({ color: metal, width: 0.6, alpha: intensity * 0.45 });
    g.circle(0, 0, 59).stroke({ color: primary, width: 0.8, alpha: intensity * 0.65 });
    if (prefs.particles !== 'off') {
      const runeCount = prefs.particles === 'high' ? 12 : 6;
      for (let i = 0; i < runeCount; i++) {
        const angle = i * TAU / runeCount + (moving ? time * 0.035 : 0);
        const x = Math.cos(angle) * 59;
        const y = Math.sin(angle) * 59;
        diamond(g, x, y, i % 3 ? 1.5 : 2.8, i % 2 ? metal : secondary, intensity * 0.8);
        g.moveTo(x * 0.89, y * 0.89).lineTo(x * 0.94, y * 0.94)
          .stroke({ color: secondary, width: 0.7, alpha: intensity * 0.5 });
      }
      if (this.skin === 'void-blade') {
        for (const side of [-1, 1]) {
          g.arc(side * 9, 5, 46, side < 0 ? Math.PI * 0.65 : -Math.PI * 0.35,
            side < 0 ? Math.PI * 1.36 : Math.PI * 0.36)
            .stroke({ color: primary, width: 1.5, alpha: intensity * 0.62 });
        }
      } else if (this.skin === 'halo-fighter') {
        g.ellipse(0, 0, 68, 23).stroke({ color: secondary, width: 0.8, alpha: intensity * 0.48 });
        g.ellipse(0, 0, 26, 67).stroke({ color: metal, width: 0.8, alpha: intensity * 0.4 });
      } else if (this.skin === 'aegis-frame') {
        g.poly([-48, -28, -48, 19, 0, 62, 48, 19, 48, -28])
          .stroke({ color: metal, width: 0.9, alpha: intensity * 0.6 });
      } else {
        g.ellipse(-14, 0, 42, 59).stroke({ color: primary, width: 0.9, alpha: intensity * 0.48 });
        g.ellipse(14, 0, 42, 59).stroke({ color: secondary, width: 0.9, alpha: intensity * 0.48 });
      }
    }
    // The native collar adds crisp glyphs above the generated nozzle orb.
    nozzle.circle(0, this.nozzleY, 3.3).fill({ color: core, alpha: 0.14 + charge * 0.28 });
    if (awakened && !charging) {
      nozzle.circle(0, this.nozzleY, 10 + charge * 3)
        .stroke({ color: primary, width: 0.7, alpha: 0.14 + charge * 0.2 });
      nozzle.ellipse(0, this.nozzleY, 16, 4).stroke({ color: secondary, width: 0.6, alpha: 0.24 * pulse });
    }
    if (prefs.particles !== 'off') {
      const glintCount = prefs.particles === 'high' ? 6 : 2;
      for (let i = 0; i < glintCount; i++) {
        const [x, y] = GLINT_LOCATIONS[i];
        const shimmer = moving ? Math.pow(Math.max(0, Math.sin(time * 0.95 + i * 1.7)), 5) : 0.2;
        star(this.glints, x, y, 2 + shimmer * 1.3, i % 2 ? secondary : core, shimmer * 0.38);
      }
    }
  }

  destroy() {
    if (this.destroyed) return;
    this.destroyed = true;
    this.rear.destroy({ children: true });
    this.front.destroy({ children: true });
    this.effects.destroy({ children: true });
    this.shots.length = 0;
    this.flashes.length = 0;
  }
}
