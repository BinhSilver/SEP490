import { Graphics } from 'pixi.js';
import { drawOrnateHyperBeam, isOrnateBeamSkin } from './OrnateHyperBeam';

type BeamPhase = 'idle' | 'charge' | 'firing' | 'cooldown';
export interface BeamVisual {
  phase: BeamPhase; skin: string; x: number; y: number; now: number; elapsed: number;
  motion: boolean; particles: 'high' | 'low' | 'off'; color: number;
}

/** Only draws the weapon. Damage, charge and cooldown remain in the match store. */
export function drawHyperBeam(g: Graphics, v: BeamVisual) {
  if (isOrnateBeamSkin(v.skin)) {
    drawOrnateHyperBeam(g, v);
    return;
  }
  drawLegacyHyperBeam(g, v);
}

// Existing planes retain their original weapon art.
function drawLegacyHyperBeam(g: Graphics, v: BeamVisual) {
  g.clear();
  if (v.phase !== 'charge' && v.phase !== 'firing') return;
  const halo = v.skin === 'plane-halo-fighter';
  const aegis = v.skin === 'plane-aegis-frame';
  const split = v.skin === 'plane-split-core';
  const voidBlade = v.skin === 'plane-void-blade';
  const color = halo ? 0xffce73 : aegis ? 0x70d9ff : split ? 0xb5a0ff : voidBlade ? 0xff416b : v.color;
  const accent = split ? 0x77efff : halo ? 0xfff0bd : aegis ? 0xc5f3ff : 0xffbfca;
  const t = v.motion ? v.now / 1000 : 0;
  const x = v.x, y = v.y, top = -24, height = y - top;
  const strength = v.phase === 'charge' ? Math.min(1, v.elapsed / 900) : v.motion ? Math.min(1, v.elapsed / 150) : 1;
  const pulse = 1 + Math.sin(t * 9) * .025;

  function ring(radius: number, squash: number, alpha: number, cy = y, width = 1.4) {
    g.ellipse(x, cy, radius, radius * squash).stroke({ color, width, alpha });
  }
  function star(cx: number, cy: number, size: number, alpha: number) {
    g.moveTo(cx - size, cy).lineTo(cx + size, cy).stroke({ color: accent, width: 1, alpha });
    g.moveTo(cx, cy - size * 1.6).lineTo(cx, cy + size * 1.6).stroke({ color: 0xffffff, width: 1, alpha });
  }
  if (v.phase === 'charge') {
    const r = 18 + strength * 19;
    ring(r + 10, halo ? .42 : .7, .65);
    ring(r + 5, .35, .45, y + 5);
    for (let layer = 4; layer >= 1; layer--) {
      g.circle(x, y, (5 + strength * 9) * layer).fill({ color, alpha: .028 + (4 - layer) * .025 });
    }
    g.circle(x, y, 3 + strength * 6).fill({ color: 0xf9fdff, alpha: .9 });
    const count = v.particles === 'off' ? 0 : v.particles === 'low' ? 4 : 10;
    for (let i = 0; i < count; i++) {
      const angle = i * Math.PI * 2 / count + t * .55;
      const travel = v.motion ? (t * .7 + i / count) % 1 : .5;
      const distance = 12 + (1 - travel) * 66;
      const px = x + Math.cos(angle) * distance, py = y + Math.sin(angle) * distance * .75;
      g.moveTo(px, py).lineTo(px + Math.cos(angle) * 9, py + Math.sin(angle) * 7)
        .stroke({ color: accent, width: 1.6, alpha: travel * .8 });
    }
    return;
  }

  // A softly layered, tapered plume; the white core stays narrow enough to see targets.
  function plume(cx: number, width: number, shade: number, alpha: number) {
    g.moveTo(cx - 3, y)
      .bezierCurveTo(cx - width * .6, y - height * .12, cx - width, y - height * .5, cx - width * .82, top)
      .lineTo(cx + width * .82, top)
      .bezierCurveTo(cx + width, y - height * .5, cx + width * .6, y - height * .12, cx + 3, y)
      .closePath().fill({ color: shade, alpha: alpha * strength });
  }
  for (let layer = 5; layer >= 1; layer--) plume(x, (12 + layer * 10) * pulse, color, .025 + (5 - layer) * .012);

  if (split) {
    // Two star cores weave around one another rather than forming a solid rectangle.
    for (const side of [-1, 1]) {
      const shade = side === 1 ? accent : color;
      for (const [width, alpha] of [[15, .12], [7, .65], [2.5, .95]]) {
        g.moveTo(x + side * 12, y)
          .bezierCurveTo(x + side * 34, y - height * .3, x - side * 32, y - height * .65, x + side * 16, top)
          .stroke({ color: width < 3 ? 0xffffff : shade, width, alpha: alpha * strength, cap: 'round' });
      }
    }
  } else if (voidBlade) {
    // A crimson cutting edge and two swept crescents, with an open centre.
    plume(x, 19, 0x7a163a, .46);
    g.moveTo(x, y + 5).lineTo(x - 9, y - height * .28).lineTo(x - 4, top)
      .lineTo(x + 4, top).lineTo(x + 9, y - height * .28).closePath().fill({ color: 0xfff2f6, alpha: .88 * strength });
    for (const side of [-1, 1]) {
      g.moveTo(x, y - 3).bezierCurveTo(x + side * 55, y - 38, x + side * 39, y - 125, x + side * 12, y - 180)
        .stroke({ color, width: 2, alpha: .8 * strength, cap: 'round' });
    }
  } else {
    plume(x, aegis ? 24 : 20, color, .3);
    plume(x, 8, 0xffffff, .88);
    if (aegis) {
      for (const side of [-1, 1]) {
        g.moveTo(x + side * 27, y + 12).lineTo(x + side * 20, y - 35).lineTo(x + side * 11, top)
          .stroke({ color: accent, width: 3, alpha: .75 * strength, cap: 'round' });
        const wing = [x + side * 16, y + 15, x + side * 61, y - 2, x + side * 47, y - 22, x + side * 21, y - 10];
        g.poly(wing).fill({ color, alpha: .12 * strength }).stroke({ color: accent, width: 1, alpha: .55 * strength });
      }
    }
  }
  const gateCount = halo ? 3 : 2;
  for (let i = 0; i < gateCount; i++) {
    const cy = y - (halo ? 24 + i * 69 : 8 + i * 19);
    const radius = (halo ? 38 - i * 4 : 26 + i * 12) * pulse;
    ring(radius, .24, .65 * strength, cy, halo ? 2.3 : 1.2);
    ring(radius + 4, .24, .2 * strength, cy, 3);
  }
  for (let layer = 4; layer >= 1; layer--) g.ellipse(x, y, 9 * layer, 4 * layer).fill({ color, alpha: .06 * strength });
  g.ellipse(x, y, 11, 5).fill({ color: 0xffffff, alpha: .9 * strength });
  const count = !v.motion || v.particles === 'off' ? 0 : v.particles === 'low' ? 5 : 14;
  for (let i = 0; i < count; i++) {
    const travel = (t * .65 + i / count) % 1;
    const side = i % 2 ? -1 : 1;
    const px = x + side * (20 + i % 4 * 9) * Math.sin(travel * Math.PI);
    const py = y - travel * height;
    star(px, py, 2 + i % 3, Math.sin(travel * Math.PI) * .65 * strength);
  }
}
