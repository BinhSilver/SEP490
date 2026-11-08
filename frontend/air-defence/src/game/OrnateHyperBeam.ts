import { Graphics } from 'pixi.js';
import type { BeamVisual } from './HyperBeamEffect';

type Theme = 'halo' | 'aegis' | 'split' | 'void';
interface Palette { theme: Theme; light: number; deep: number; accent: number; hot: number }
const PALETTES: Record<string, Palette> = {
  'plane-halo-fighter': { theme: 'halo', light: 0xffbf48, deep: 0xd6791e, accent: 0xffe8a0, hot: 0xfffcdf },
  'plane-aegis-frame': { theme: 'aegis', light: 0x57cfff, deep: 0x306fcc, accent: 0xc2f5ff, hot: 0xf0fdff },
  'plane-split-core': { theme: 'split', light: 0xb97aff, deep: 0x743bf4, accent: 0x6cf5ff, hot: 0xf8eeff },
  'plane-void-blade': { theme: 'void', light: 0xff304b, deep: 0xb00d3d, accent: 0xff8ea4, hot: 0xfff0f5 },
};
const TAU = Math.PI * 2;
const clamp = (value: number) => Math.max(0, Math.min(1, value));

export function isOrnateBeamSkin(skin: string) { return skin in PALETTES; }

/** Charge colours for the shared super-cannon charge (ChargeFx). */
export function ornateColors(skin: string) {
  const p = PALETTES[skin];
  return p ? { primary: p.light, accent: p.accent, hot: p.hot } : null;
}

/** Render-only ornaments. The store remains the sole owner of damage and phase timing. */
export function drawOrnateHyperBeam(g: Graphics, v: BeamVisual) {
  g.clear();
  // The charge itself is drawn by ChargeFx for every plane; this draws the firing beam.
  if (v.phase !== 'firing') return;
  const p = PALETTES[v.skin];
  if (!p) return;
  const { theme, light, deep, accent, hot } = p;
  const x = v.x, y = v.y, top = -24, height = Math.max(1, y - top);
  const t = v.motion ? v.now / 1000 : 0;
  const strength = !v.motion ? 1 : clamp(v.elapsed / 150);
  const detail = v.particles === 'high' ? 1 : .65;
  const pulse = 1 + Math.sin(t * 2.1) * .016;
  const shaftWidth = theme === 'aegis' ? 37 : theme === 'split' ? 43 : 45;
  const fit = Math.min(1, Math.max(.65, x / 150));

  function line(points: number[], color: number, width: number, alpha: number) {
    if (points.length < 4) return;
    g.moveTo(points[0], points[1]);
    for (let i = 2; i < points.length; i += 2) g.lineTo(points[i], points[i + 1]);
    g.stroke({ color, width, alpha: alpha * strength, cap: 'round', join: 'round' });
  }

  function diamond(cx: number, cy: number, size: number, color: number, alpha: number, stretch = 1.8) {
    size *= 1.15;
    const h = size * stretch;
    g.poly([cx, cy - h * 1.22, cx + size * 1.55, cy, cx, cy + h * 1.22, cx - size * 1.55, cy])
      .fill({ color, alpha: alpha * .1 * strength });
    g.poly([cx, cy - h, cx + size, cy, cx, cy + h, cx - size, cy])
      .fill({ color, alpha: alpha * .66 * strength })
      .stroke({ color: accent, width: 1.15, alpha: alpha * strength });
    // Two opposed crystal planes catch a white hot highlight and a saturated tinted shadow.
    g.poly([cx, cy - h * .86, cx + size * .65, cy, cx, cy + h * .67, cx - size * .17, cy])
      .fill({ color: hot, alpha: alpha * .76 * strength });
    line([cx, cy - h * .88, cx - size * .58, cy, cx, cy + h * .75], hot, .85, alpha * .9);
  }

  function sparkle(cx: number, cy: number, size: number, color = hot, alpha = .9) {
    const arm = size * .19;
    g.poly([cx, cy - size * 2, cx + arm, cy - arm, cx + size, cy,
      cx + arm, cy + arm, cx, cy + size * 2, cx - arm, cy + arm,
      cx - size, cy, cx - arm, cy - arm]).fill({ color, alpha: alpha * strength });
    line([cx - size * 2.5, cy, cx + size * 2.5, cy], color, .65, alpha * .36);
  }

  function arc(cx: number, cy: number, radius: number, squash: number, from: number, to: number,
    color: number, width: number, alpha: number) {
    const count = Math.max(10, Math.ceil(Math.abs(to - from) * radius / 7));
    const points: number[] = [];
    for (let i = 0; i <= count; i++) {
      const a = from + (to - from) * i / count;
      points.push(cx + Math.cos(a) * radius, cy + Math.sin(a) * radius * squash);
    }
    line(points, color, width, alpha);
  }

  function polygon(cx: number, cy: number, radius: number, sides: number, squash = 1, angle = -Math.PI / 2,
    color = accent, alpha = .7, width = 1) {
    const points: number[] = [];
    for (let i = 0; i <= sides; i++) {
      const a = angle + i * TAU / sides;
      points.push(cx + Math.cos(a) * radius, cy + Math.sin(a) * radius * squash);
    }
    line(points, color, width, alpha);
  }

  // Angular script strokes rather than text: crisp, tiny glyphs remain legible at game scale.
  function rune(cx: number, cy: number, angle: number, size: number, index: number, alpha: number) {
    const c = Math.cos(angle), s = Math.sin(angle);
    const point = (u: number, w: number) => [cx + (u * c - w * s) * size, cy + (u * s + w * c) * size];
    const shape = index % 3 === 0 ? [-.6, .8, -.6, -.8, .6, -.25, -.6, .25]
      : index % 3 === 1 ? [-.65, -.8, .65, -.8, 0, 0, .65, .8, -.65, .8]
      : [-.55, .8, 0, -.8, .55, .8, -.55, .2, .55, .2];
    const points: number[] = [];
    for (let i = 0; i < shape.length; i += 2) points.push(...point(shape[i], shape[i + 1]));
    line(points, accent, .75, alpha);
  }

  function seal(cx: number, cy: number, radius: number, squash: number, alpha: number, rotation = 0) {
    // Open, interleaved rings with radial etching and four jewel cardinal points.
    arc(cx, cy, radius + 2, squash, 0, TAU, light, 7, alpha * .18);
    const sectors = theme === 'aegis' ? 6 : 8;
    for (let j = 0; j < sectors; j++) {
      const a = j * TAU / sectors + rotation;
      arc(cx, cy, radius, squash, a + .07, a + TAU / sectors - .07, accent, 1.65, alpha);
      arc(cx, cy, radius - 7, squash, a + .12, a + TAU / sectors - .12, light, 1.1, alpha * .85);
      const ca = Math.cos(a), sa = Math.sin(a);
      line([cx + ca * (radius - 3), cy + sa * (radius - 3) * squash,
        cx + ca * (radius + 5), cy + sa * (radius + 5) * squash], accent, 1, alpha * .8);
    }
    const runes = theme === 'aegis' ? 12 : 20;
    for (let i = 0; i < runes; i++) {
      const a = (i + .5) * TAU / runes + rotation;
      rune(cx + Math.cos(a) * (radius - 3.8), cy + Math.sin(a) * (radius - 3.8) * squash,
        a + Math.PI / 2, 1.4, i, alpha * .68);
    }
    for (let i = 0; i < 4; i++) {
      const a = i * Math.PI / 2;
      const px = cx + Math.cos(a) * (radius + 6), py = cy + Math.sin(a) * (radius + 6) * squash;
      if (i % 2 === 0) diamond(px, py, 3.8, accent, alpha, 1.5);
      else sparkle(px, py, 4.1, hot, alpha);
    }
  }

  function glow(cx: number, cy: number, radius: number, squash: number) {
    for (let i = 5; i >= 1; i--) {
      g.ellipse(cx, cy, radius * i / 2, radius * i / 2 * squash)
        .fill({ color: light, alpha: (.026 + (5 - i) * .012) * strength });
    }
  }

  function plume(width: number, color: number, alpha: number) {
    width *= fit * pulse;
    g.moveTo(x - 3, y + 3)
      .bezierCurveTo(x - width * .3, y - height * .1, x - width * .88, y - height * .55, x - width, top)
      .lineTo(x + width, top)
      .bezierCurveTo(x + width * .88, y - height * .55, x + width * .3, y - height * .1, x + 3, y + 3)
      .closePath().fill({ color, alpha: alpha * strength });
  }

  // A filled, tapered ribbon has its own highlight edge, unlike a uniformly thick line.
  function ribbon(side: number, offset: number, wave: number, phase: number, color: number, thickness: number,
    alpha: number, start = 0, end = 1) {
    const steps = 34;
    const left: number[] = [], right: number[] = [], edge: number[] = [], bloom: number[] = [], facet: number[] = [];
    for (let i = 0; i <= steps; i++) {
      const u = start + (end - start) * i / steps;
      const f = i / steps;
      const envelope = Math.sin(f * Math.PI);
      const spread = Math.min(1, u * 5);
      const px = x + (side * offset + Math.sin(u * TAU * 1.4 + phase + t * 1.45) * wave) * spread * fit;
      const py = y - height * u;
      const w = (thickness * (.25 + .75 * envelope)) * fit;
      left.push(px - w, py); right.push(px + w, py); edge.push(px + side * w * .45, py);
      if (i % 2 === 0) bloom.push(px + side * w * .45, py);
      facet.push(px + side * w * .07, py);
    }
    const points = [...left];
    for (let i = right.length - 2; i >= 0; i -= 2) points.push(right[i], right[i + 1]);
    // A broad tinted bloom, saturated body and luminous internal plane read as one energy ribbon.
    if (thickness > 2) line(bloom, color, thickness * 3.6, alpha * .14);
    g.poly(points).fill({ color, alpha: alpha * strength });
    if (thickness > 2) {
      const facetPoints = [...facet];
      for (let i = edge.length - 2; i >= 0; i -= 2) facetPoints.push(edge[i], edge[i + 1]);
      g.poly(facetPoints).fill({ color: color === hot ? hot : accent, alpha: alpha * .57 * strength });
    }
    line(edge, color === accent ? hot : accent, thickness > 2 ? 1.35 : .85, alpha * .96);
  }

  function scythe(side: number, cy: number, radius: number, length: number, alpha: number) {
    // Two unequal Bézier curves meet in a razor tip to create an actual crescent blade.
    const cx = x + side * 9 * fit;
    const width = radius * fit;
    g.moveTo(cx, cy + length * .49)
      .bezierCurveTo(cx + side * width * 1.36, cy + length * .29,
        cx + side * width * 1.37, cy - length * .18, cx + side * width * .28, cy - length * .58)
      .bezierCurveTo(cx + side * width * 1.03, cy - length * .15,
        cx + side * width * .96, cy + length * .16, cx, cy + length * .49)
      .closePath().fill({ color: light, alpha: alpha * .68 * strength });
    // A second curved crystal plane gives the blade volume inside the crisp outer silhouette.
    g.moveTo(cx, cy + length * .49)
      .bezierCurveTo(cx + side * width * 1.28, cy + length * .23,
        cx + side * width * 1.28, cy - length * .17, cx + side * width * .28, cy - length * .58)
      .bezierCurveTo(cx + side * width * 1.14, cy - length * .17,
        cx + side * width * 1.01, cy + length * .16, cx, cy + length * .49)
      .closePath().fill({ color: accent, alpha: alpha * .58 * strength });
    g.moveTo(cx, cy + length * .49)
      .bezierCurveTo(cx + side * width * 1.36, cy + length * .29,
        cx + side * width * 1.37, cy - length * .18, cx + side * width * .28, cy - length * .58)
      .stroke({ color: light, width: 10, alpha: alpha * .16 * strength, cap: 'round' });
    g.moveTo(cx, cy + length * .49)
      .bezierCurveTo(cx + side * width * 1.36, cy + length * .29,
        cx + side * width * 1.37, cy - length * .18, cx + side * width * .28, cy - length * .58)
      .stroke({ color: hot, width: 1.65, alpha: alpha * strength, cap: 'round' });
    sparkle(cx + side * width * .91, cy - length * .22, 4.8, hot, alpha * .95);
  }

  function wing(side: number, cy: number, scale: number, alpha: number) {
    const shape = [x + side * 17 * scale, cy + 13 * scale, x + side * 40 * scale, cy - 7 * scale,
      x + side * 77 * scale, cy - 20 * scale, x + side * 56 * scale, cy + 6 * scale,
      x + side * 33 * scale, cy + 7 * scale];
    g.poly(shape).fill({ color: light, alpha: alpha * .44 * strength })
      .stroke({ color: accent, width: 1.4, alpha: alpha * .94 * strength });
    g.poly([x + side * 26 * scale, cy + 8 * scale, x + side * 40 * scale, cy - 7 * scale,
      x + side * 77 * scale, cy - 20 * scale, x + side * 45 * scale, cy - 1 * scale])
      .fill({ color: accent, alpha: alpha * .39 * strength });
    line([x + side * 23 * scale, cy + 9 * scale, x + side * 45 * scale, cy - 1 * scale,
      x + side * 66 * scale, cy - 13 * scale], hot, 1.25, alpha * .92);
    for (let j = 0; j < 3; j++) {
      line([x + side * (33 + j * 10) * scale, cy + (3 - j * 4) * scale,
        x + side * (40 + j * 10) * scale, cy + (9 - j * 5) * scale], light, 1.2, alpha * .5);
    }
  }

  function particles() {
    const count = v.particles === 'off' ? 0 : v.particles === 'low' ? 7 : 24;
    for (let i = 0; i < count; i++) {
      const u = v.motion ? (t * .35 + i * .618034) % 1 : (i + .5) / count;
      const side = i % 2 ? -1 : 1;
      const px = x + side * (23 + (i % 5) * 11 + Math.sin(u * Math.PI) * 12) * fit;
      const py = y - height * (.05 + u * .91);
      const alpha = (.24 + Math.sin(u * Math.PI) * .56) * detail;
      const color = theme === 'split' && side === 1 ? accent : light;
      if (i % 3 === 0) diamond(px, py, 2 + i % 4, color, alpha);
      else if (i % 3 === 1) sparkle(px, py, 1.6 + i % 3 * .6, hot, alpha);
      else {
        line([px, py + 9, px, py - 4], color, .75, alpha * .7);
        g.circle(px, py, .9).fill({ color: hot, alpha: alpha * strength });
      }
    }
  }

  // Concentric tinted plumes create restrained bloom; the solid cutting core stays only 2–6px wide.
  plume(shaftWidth + 18, deep, .045);
  plume(shaftWidth + 10, light, .06);
  plume(shaftWidth, light, .075);
  plume(shaftWidth * .7, light, .115);
  plume(17, light, .19);

  if (theme === 'split') {
    // A pair of independent star streams share a constellation, crossing without becoming a solid bar.
    for (const side of [-1, 1]) {
      const color = side === 1 ? accent : light;
      ribbon(side, 2, 21, side === 1 ? 0 : Math.PI, color, 9, .32);
      ribbon(side, 2, 21, side === 1 ? 0 : Math.PI, color, 4.8, .86);
      ribbon(side, 2, 21, side === 1 ? 0 : Math.PI, hot, 1.05, .97);
      ribbon(side, 35, 8, side * .5, color, 2.6, .64, .08);
    }
    for (let i = 0; i < 5; i++) {
      const u = .17 + i * .165, cy = y - height * u;
      const a = Math.sin(u * TAU * 1.4 + t * 1.45) * 21 * fit;
      const b = -a;
      line([x + a, cy, x + 36 * fit, cy - 14, x + b, cy - 26,
        x - 36 * fit, cy - 14, x + a, cy], i % 2 ? light : accent, .75, .35);
      sparkle(x + 36 * fit, cy - 14, 2.3, accent, .8);
      sparkle(x - 36 * fit, cy - 14, 2.3, light, .8);
    }
  } else {
    // Main hot edge plus a pair of pearl filaments — bright centre, translucent margins.
    plume(10.5, light, .47);
    plume(4.4, accent, .72);
    plume(2.3, hot, .97);
    for (const side of [-1, 1]) {
      ribbon(side, 22, 14, side * 1.6, light, theme === 'void' ? 8.5 : 6.4, .78);
      ribbon(side, 30, 10, side * .6 + 1, accent, 3.1, .79);
      ribbon(side, 8, 4, side * 1.2, hot, .85, .8);
    }
  }

  if (theme === 'void') {
    // Spectral scythe blades frame the braided crimson column in two sweeping tiers.
    for (const side of [-1, 1]) {
      scythe(side, y - height * .28, 62, Math.min(180, height * .49), .82);
      scythe(side, y - height * .7, 43, Math.min(168, height * .4), .62);
      line([x + side * 49 * fit, y - 24, x + side * 66 * fit, y - 65,
        x + side * 74 * fit, y - height * .5], light, .65, .34);
    }
    for (let i = 0; i < 3; i++) {
      const cy = y - height * (.2 + i * .255);
      diamond(x, cy, 4.7, light, .94, 2.3);
      for (const side of [-1, 1]) diamond(x + side * (48 - i * 7) * fit, cy - 12, 5.1, accent, .89);
    }
    seal(x, y - 3, 46 * fit, .79, .85, t * .1);
    seal(x, y - 14, 33 * fit, .34, .57, -t * .12);
  } else if (theme === 'halo') {
    // Golden judgment gates are decorated armillary hoops rather than repeated plain ellipses.
    for (let i = 0; i < 3; i++) {
      const cy = y - height * (.09 + i * .255), radius = (52 - i * 6) * fit;
      seal(x, cy, radius, .29 + i * .04, .74 - i * .08, t * .08 * (i % 2 ? -1 : 1));
      arc(x, cy, radius + 9, .3, Math.PI * 1.12, Math.PI * 1.88, light, 2, .44);
      for (const side of [-1, 1]) {
        diamond(x + side * (radius + 6), cy, 5.4, accent, .94, 1.35);
        line([x + side * (radius - 5), cy - 4, x + side * (radius + 17), cy - 17,
          x + side * (radius + 11), cy - 2], light, 1.15, .6);
      }
      sparkle(x, cy - radius * .35 - 4, 3.3, hot, .85);
    }
    seal(x, y - 3, 40 * fit, .76, .74, -t * .1);
    for (const side of [-1, 1]) {
      for (let i = 0; i < 5; i++) {
        const cy = y - 12 - i * 10;
        const xx = x + side * (36 + i * 4) * fit;
        g.poly([xx, cy + 4, xx + side * 19 * fit, cy - 10, xx + side * 5 * fit, cy - 8])
          .fill({ color: light, alpha: .49 * strength }).stroke({ color: accent, width: 1.05, alpha: .84 * strength });
      }
    }
  } else if (theme === 'aegis') {
    // Platinum wing rails and guardian lattice are an angular counterpart to the celestial hoops.
    for (const side of [-1, 1]) {
      const rail = [x + side * 24 * fit, y + 11, x + side * 37 * fit, y - 32,
        x + side * 29 * fit, y - height * .4, x + side * 34 * fit, top];
      line(rail, light, 9, .23);
      line(rail, accent, 2.6, .94);
      line([x + side * 40 * fit, y - 32, x + side * 47 * fit, y - height * .36,
        x + side * 43 * fit, top], light, .7, .46);
      wing(side, y, fit, .93);
    }
    for (let i = 0; i < 3; i++) {
      const cy = y - height * (.14 + i * .27), r = (43 - i * 3) * fit;
      polygon(x, cy, r, 6, .4, -Math.PI / 2, light, .19, 6.5);
      polygon(x, cy, r, 6, .4, -Math.PI / 2, accent, .88, 1.65);
      polygon(x, cy, r - 6, 6, .4, -Math.PI / 2, light, .37, .8);
      for (const side of [-1, 1]) {
        diamond(x + side * r * .866, cy, 4.7, accent, .94, 1.6);
        line([x + side * 14 * fit, cy + 9, x + side * 21 * fit, cy - 9,
          x + side * 29 * fit, cy + 9], accent, .8, .5);
      }
      sparkle(x, cy - r * .4, 2.5, hot, .8);
    }
    seal(x, y - 2, 36 * fit, .75, .75);
    polygon(x, y - 2, 49 * fit, 6, .76, -Math.PI / 2, accent, .58);
  } else {
    seal(x, y - 2, 43 * fit, .71, .76, t * .11);
    for (const side of [-1, 1]) {
      const color = side === 1 ? accent : light;
      arc(x + side * 14 * fit, y - 4, 31 * fit, .52, 0, TAU, color, 1.2, .72);
      diamond(x + side * 52 * fit, y - 12, 5.2, color, .87);
      sparkle(x + side * 13 * fit, y - 6, 5, color, .96);
    }
  }

  // A small faceted nozzle anchors every theme. It never expands into an opaque screen wash.
  glow(x, y - 4, 13, .58);
  if (theme !== 'split') sparkle(x, y - 5, 8, hot);
  diamond(x, y - 3, 5, accent, .9, 1.6);
  line([x - 28 * fit, y - 4, x + 28 * fit, y - 4], accent, .65, .38);
  particles();
}
