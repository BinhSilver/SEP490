import { assetUrl } from '../assets';
import { CatPlayer, FallingObject } from '../types/game';
import { layoutCardText } from './CardText';

export class SpriteRenderer {

  // ─────────────────────────────────────────────────
  //  MAIN CAT CHARACTER
  // ─────────────────────────────────────────────────
  public static drawCat(ctx: CanvasRenderingContext2D, cat: CatPlayer, time: number) {
    ctx.save();
    ctx.translate(cat.x, cat.y);

    const isFlipped = cat.state === 'run_left';
    if (isFlipped) ctx.scale(-1, 1);

    // Wobble / state animations
    let bounceY = 0;
    let tailAngle = Math.sin(time * 0.0038) * 0.22;
    let basketLift = 0;
    let eyeState: 'normal' | 'happy' | 'dizzy' | 'blink' = 'normal';
    let squishX = 1, squishY = 1;

    switch (cat.state) {
      case 'idle':
        bounceY = Math.sin(time * 0.0035) * 2.5;
        if (Math.sin(time * 0.0018) > 0.93) eyeState = 'blink';
        break;
      case 'run_left':
      case 'run_right':
        bounceY = -Math.abs(Math.sin(time * 0.018)) * 7;
        tailAngle = Math.sin(time * 0.022) * 0.45;
        squishX = 1 + Math.sin(time * 0.018) * 0.06;
        squishY = 1 - Math.sin(time * 0.018) * 0.04;
        break;
      case 'catch_success':
        bounceY = -18 + Math.sin(time * 0.025) * 4;
        basketLift = -14;
        eyeState = 'happy';
        squishX = 1.08; squishY = 0.92;
        break;
      case 'catch_wrong':
        bounceY = Math.sin(time * 0.03) * 3;
        eyeState = 'dizzy';
        squishX = 0.94; squishY = 1.06;
        break;
      case 'win':
        bounceY = -20 + Math.abs(Math.sin(time * 0.015)) * 15;
        eyeState = 'happy';
        basketLift = -16;
        break;
    }

    ctx.scale(squishX, squishY);
    const W = cat.width;
    const H = cat.height;
    const bY = bounceY;

    // ─ 1. Ground Shadow ─
    ctx.fillStyle = `rgba(0,0,0,${Math.max(0.06, 0.22 - Math.abs(bY) * 0.007)})`;
    ctx.beginPath();
    ctx.ellipse(0, H * 0.42, W * 0.34, 7, 0, 0, Math.PI * 2);
    ctx.fill();

    // ─ Shield Aura ─
    if (cat.shieldActive) {
      const shGrad = ctx.createRadialGradient(0, bY - H * 0.1, 10, 0, bY - H * 0.1, W * 0.65);
      shGrad.addColorStop(0, 'rgba(52, 211, 153, 0)');
      shGrad.addColorStop(0.7, 'rgba(52, 211, 153, 0.12)');
      shGrad.addColorStop(1, 'rgba(52, 211, 153, 0.5)');
      ctx.fillStyle = shGrad;
      ctx.beginPath();
      ctx.arc(0, bY - H * 0.1, W * 0.65, 0, Math.PI * 2);
      ctx.fill();

      ctx.strokeStyle = `rgba(52, 211, 153, ${0.5 + 0.4 * Math.sin(time * 0.008)})`;
      ctx.lineWidth = 2.5;
      ctx.setLineDash([5, 4]);
      ctx.beginPath();
      ctx.arc(0, bY - H * 0.1, W * 0.65, 0, Math.PI * 2);
      ctx.stroke();
      ctx.setLineDash([]);
    }

    // ─ 2. Tail ─
    ctx.save();
    ctx.translate(-W * 0.27, bY + H * 0.08);
    ctx.rotate(-0.45 + tailAngle);

    // Tail shadow
    ctx.fillStyle = 'rgba(0,0,0,0.12)';
    ctx.beginPath();
    ctx.roundRect(-7, -32, 14, 38, 8);
    ctx.fill();

    // Orange tail
    const tailGrad = ctx.createLinearGradient(-7, -32, 7, 0);
    tailGrad.addColorStop(0, '#FF9F43');
    tailGrad.addColorStop(1, '#FF8C00');
    ctx.fillStyle = tailGrad;
    ctx.beginPath();
    ctx.roundRect(-6, -32, 12, 36, 8);
    ctx.fill();

    // White tip
    ctx.fillStyle = '#FFFDE7';
    ctx.beginPath();
    ctx.roundRect(-5, -32, 10, 11, 6);
    ctx.fill();

    ctx.restore();

    // ─ 3. Body ─
    // Body shadow for depth
    ctx.fillStyle = 'rgba(0,0,0,0.12)';
    ctx.beginPath();
    ctx.roundRect(-W * 0.31 + 3, bY - H * 0.22 + 4, W * 0.62, H * 0.52, 22);
    ctx.fill();

    // Body gradient (orange fur)
    const bodyGrad = ctx.createLinearGradient(-W * 0.3, bY - H * 0.22, W * 0.3, bY + H * 0.3);
    bodyGrad.addColorStop(0, '#FFAD5A');
    bodyGrad.addColorStop(0.5, '#FF9F43');
    bodyGrad.addColorStop(1, '#E8890A');
    ctx.fillStyle = bodyGrad;
    ctx.beginPath();
    ctx.roundRect(-W * 0.31, bY - H * 0.22, W * 0.62, H * 0.52, 22);
    ctx.fill();

    // White belly
    const bellyGrad = ctx.createRadialGradient(-2, bY, 4, 0, bY, W * 0.25);
    bellyGrad.addColorStop(0, '#FFFDE7');
    bellyGrad.addColorStop(1, '#FFF9C4');
    ctx.fillStyle = bellyGrad;
    ctx.beginPath();
    ctx.ellipse(0, bY + H * 0.06, W * 0.22, H * 0.22, 0, 0, Math.PI * 2);
    ctx.fill();

    // ─ 4. Feet with dynamic running animation ─
    const isRunning = cat.state === 'run_left' || cat.state === 'run_right';
    const footSwing1 = isRunning ? Math.sin(time * 0.025) * 7 : 0;
    const footSwing2 = isRunning ? -Math.sin(time * 0.025) * 7 : 0;

    [
      [-W * 0.16 + footSwing1, bY + H * 0.28],
      [W * 0.16 + footSwing2, bY + H * 0.28]
    ].forEach(([fx, fy]) => {
      const footGrad = ctx.createRadialGradient(fx, fy, 1, fx, fy, 11);
      footGrad.addColorStop(0, '#FFF9EE');
      footGrad.addColorStop(1, '#FFE0B2');
      ctx.fillStyle = footGrad;
      ctx.beginPath();
      ctx.ellipse(fx, fy, 11, 8, 0, 0, Math.PI * 2);
      ctx.fill();
    });

    // ─ 5. Red Scarf ─
    const scarfGrad = ctx.createLinearGradient(-W * 0.28, bY - H * 0.24, W * 0.28, bY - H * 0.16);
    scarfGrad.addColorStop(0, '#E53935');
    scarfGrad.addColorStop(1, '#B71C1C');
    ctx.fillStyle = scarfGrad;
    ctx.beginPath();
    ctx.roundRect(-W * 0.27, bY - H * 0.25, W * 0.54, 11, 5);
    ctx.fill();

    // Scarf knot
    ctx.fillStyle = '#EF5350';
    ctx.beginPath();
    ctx.arc(-W * 0.16, bY - H * 0.19, 7, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#FFCDD2';
    ctx.beginPath();
    ctx.arc(-W * 0.16, bY - H * 0.19, 3, 0, Math.PI * 2);
    ctx.fill();

    // ─ 6. Head ─
    const headY = bY - H * 0.4;
    const headR = W * 0.33;

    // ─ Ears ─
    const earPositions = [
      { ox: -headR * 0.82, rotate: -0.2 },
      { ox:  headR * 0.82, rotate:  0.2, flipX: true }
    ];
    earPositions.forEach(ep => {
      ctx.save();
      ctx.translate(ep.ox, headY - headR * 0.45);
      if (ep.flipX) ctx.scale(-1, 1);

      // Outer ear
      const earGrad = ctx.createLinearGradient(-12, -24, 6, 0);
      earGrad.addColorStop(0, '#FFAD5A');
      earGrad.addColorStop(1, '#E8890A');
      ctx.fillStyle = earGrad;
      ctx.beginPath();
      ctx.moveTo(0, 0);
      ctx.lineTo(-14, -28);
      ctx.lineTo(14, -20);
      ctx.closePath();
      ctx.fill();

      // Inner ear pink
      ctx.fillStyle = '#F48FB1';
      ctx.beginPath();
      ctx.moveTo(0, -2);
      ctx.lineTo(-9, -22);
      ctx.lineTo(9, -15);
      ctx.closePath();
      ctx.fill();
      ctx.restore();
    });

    // Head base + shine
    const headGrad = ctx.createRadialGradient(-headR * 0.2, headY - headR * 0.3, 4, 0, headY, headR);
    headGrad.addColorStop(0, '#FFBD70');
    headGrad.addColorStop(0.6, '#FF9F43');
    headGrad.addColorStop(1, '#E08800');
    ctx.fillStyle = headGrad;
    ctx.beginPath();
    ctx.arc(0, headY, headR, 0, Math.PI * 2);
    ctx.fill();

    // White face mask
    const faceGrad = ctx.createRadialGradient(-2, headY + 4, 2, 0, headY + 6, headR * 0.7);
    faceGrad.addColorStop(0, '#FFFDE7');
    faceGrad.addColorStop(1, '#FFF9C4');
    ctx.fillStyle = faceGrad;
    ctx.beginPath();
    ctx.ellipse(0, headY + 7, headR * 0.72, headR * 0.58, 0, 0, Math.PI * 2);
    ctx.fill();

    // Cheek blush
    ctx.fillStyle = 'rgba(255, 150, 150, 0.25)';
    ctx.beginPath();
    ctx.ellipse(-headR * 0.52, headY + 8, 9, 6, 0, 0, Math.PI * 2);
    ctx.ellipse( headR * 0.52, headY + 8, 9, 6, 0, 0, Math.PI * 2);
    ctx.fill();

    // Pink nose
    ctx.fillStyle = '#FF8A80';
    ctx.beginPath();
    ctx.moveTo(-5, headY + 2);
    ctx.lineTo(5, headY + 2);
    ctx.lineTo(0, headY + 7);
    ctx.closePath();
    ctx.fill();

    // Mouth
    ctx.strokeStyle = '#BF360C';
    ctx.lineWidth = 1.8;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.arc(-3, headY + 9, 3.5, 0.2, Math.PI * 0.9);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(3, headY + 9, 3.5, Math.PI * 0.1, Math.PI * 0.8);
    ctx.stroke();

    // Whiskers
    ctx.strokeStyle = 'rgba(100, 80, 60, 0.45)';
    ctx.lineWidth = 1.2;
    [[-1, 1], [-1, -1]].forEach(([, ySign]) => {
      ctx.beginPath();
      ctx.moveTo(-headR * 0.5, headY + 3 + ySign * 4);
      ctx.lineTo(-headR * 1.15, headY + 1 + ySign * 6);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(headR * 0.5, headY + 3 + ySign * 4);
      ctx.lineTo(headR * 1.15, headY + 1 + ySign * 6);
      ctx.stroke();
    });

    // Eyes
    if (eyeState === 'blink') {
      ctx.strokeStyle = '#3E2723';
      ctx.lineWidth = 3;
      ctx.lineCap = 'round';
      ctx.beginPath();
      ctx.moveTo(-headR * 0.42, headY - 4);
      ctx.quadraticCurveTo(-headR * 0.28, headY - 10, -headR * 0.14, headY - 4);
      ctx.moveTo( headR * 0.14, headY - 4);
      ctx.quadraticCurveTo( headR * 0.28, headY - 10,  headR * 0.42, headY - 4);
      ctx.stroke();
    } else if (eyeState === 'happy') {
      ctx.strokeStyle = '#3E2723';
      ctx.lineWidth = 3;
      ctx.lineCap = 'round';
      ctx.beginPath();
      ctx.arc(-headR * 0.28, headY - 5, 8, Math.PI * 1.0, Math.PI * 0.0);
      ctx.arc( headR * 0.28, headY - 5, 8, Math.PI * 1.0, Math.PI * 0.0);
      ctx.stroke();
      // Small vector eye highlights
      ctx.fillStyle = '#FDD835';
      [[-headR * 0.28 + 14, headY - 14], [headR * 0.28 + 14, headY - 14]].forEach(([sx, sy]) => {
        ctx.beginPath();
        ctx.arc(sx, sy, 3, 0, Math.PI * 2);
        ctx.fill();
      });
    } else if (eyeState === 'dizzy') {
      ctx.strokeStyle = '#3E2723';
      ctx.lineWidth = 2.5;
      ctx.lineCap = 'round';
      [[-headR * 0.28, headY - 5], [headR * 0.28, headY - 5]].forEach(([ex, ey]) => {
        ctx.beginPath();
        ctx.moveTo(ex - 7, ey - 7); ctx.lineTo(ex + 7, ey + 7);
        ctx.moveTo(ex + 7, ey - 7); ctx.lineTo(ex - 7, ey + 7);
        ctx.stroke();
      });
    } else {
      // Normal big shiny eyes
      [[-headR * 0.28, headY - 5], [headR * 0.28, headY - 5]].forEach(([ex, ey]) => {
        // Eye white
        ctx.fillStyle = '#FFFDE7';
        ctx.beginPath();
        ctx.ellipse(ex, ey, 8, 10, 0, 0, Math.PI * 2);
        ctx.fill();
        // Iris
        const eyeGrad = ctx.createRadialGradient(ex - 2, ey - 2, 1, ex, ey, 7);
        eyeGrad.addColorStop(0, '#6D4C41');
        eyeGrad.addColorStop(1, '#3E2723');
        ctx.fillStyle = eyeGrad;
        ctx.beginPath();
        ctx.ellipse(ex, ey, 6, 8, 0, 0, Math.PI * 2);
        ctx.fill();
        // Catchlight
        ctx.fillStyle = '#FFFFFF';
        ctx.beginPath();
        ctx.arc(ex - 2.5, ey - 3, 2.5, 0, Math.PI * 2);
        ctx.fill();
        ctx.beginPath();
        ctx.arc(ex + 2.5, ey + 1.5, 1.5, 0, Math.PI * 2);
        ctx.fill();
      });
    }

    // ─ 7. Basket ─
    const bkY = bY - H * 0.14 + basketLift;
    ctx.save();
    ctx.translate(0, bkY);

    const bW = cat.basketWidth;
    const bH = cat.basketHeight;

    // Basket body shadow
    ctx.fillStyle = 'rgba(0,0,0,0.15)';
    ctx.beginPath();
    ctx.roundRect(-bW / 2 + 3, 4, bW - 2, bH, 8);
    ctx.fill();

    // Basket body
    const basketGrad = ctx.createLinearGradient(-bW / 2, 0, bW / 2, bH);
    basketGrad.addColorStop(0, '#A1785A');
    basketGrad.addColorStop(0.5, '#8B6348');
    basketGrad.addColorStop(1, '#6D4C37');
    ctx.fillStyle = basketGrad;
    ctx.beginPath();
    ctx.roundRect(-bW / 2, 0, bW, bH, 8);
    ctx.fill();

    // Woven lattice pattern
    ctx.save();
    ctx.beginPath();
    ctx.roundRect(-bW / 2 + 2, 2, bW - 4, bH - 4, 6);
    ctx.clip();
    ctx.strokeStyle = 'rgba(0,0,0,0.25)';
    ctx.lineWidth = 1.2;
    for (let wvX = -bW / 2 + 8; wvX < bW / 2; wvX += 12) {
      ctx.beginPath(); ctx.moveTo(wvX, 0); ctx.lineTo(wvX, bH); ctx.stroke();
    }
    for (let wvY = 6; wvY < bH; wvY += 8) {
      ctx.beginPath(); ctx.moveTo(-bW / 2 + 2, wvY); ctx.lineTo(bW / 2 - 2, wvY); ctx.stroke();
    }
    ctx.restore();

    // Rim
    const rimGrad = ctx.createLinearGradient(-bW / 2, -4, bW / 2, 4);
    rimGrad.addColorStop(0, '#C49A6C');
    rimGrad.addColorStop(0.5, '#D4A574');
    rimGrad.addColorStop(1, '#A67C52');
    ctx.fillStyle = rimGrad;
    ctx.beginPath();
    ctx.ellipse(0, 0, bW / 2 + 3, 6, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = '#7B5133';
    ctx.lineWidth = 1.5;
    ctx.stroke();

    // Inside rim glow (opening)
    ctx.fillStyle = 'rgba(40, 20, 10, 0.6)';
    ctx.beginPath();
    ctx.ellipse(0, 0, bW / 2 - 2, 4, 0, 0, Math.PI * 2);
    ctx.fill();

    ctx.restore(); // basket

    ctx.restore(); // squish
    ctx.restore(); // main
  }

  private static handDrawnImages: Map<string, HTMLImageElement> = new Map();
  private static isHandDrawnLoaded = false;

  public static initHandDrawnAssets() {
    if (this.isHandDrawnLoaded || typeof window === 'undefined') return;
    const assets = [
      { key: 'fish_blue', path: '/sprites/tasser_hand_drawn/card_fish_blue.png' },
      { key: 'fish_pink', path: '/sprites/tasser_hand_drawn/card_fish_pink.png' },
      { key: 'fish_green', path: '/sprites/tasser_hand_drawn/card_fish_green.png' },
      { key: 'paw_cookie', path: '/sprites/tasser_hand_drawn/card_paw_cookie.png' },
      { key: 'golden_fish', path: '/sprites/tasser_hand_drawn/item_golden_fish.png' },
      { key: 'clock', path: '/sprites/tasser_hand_drawn/item_clock.png' },
      { key: 'shield', path: '/sprites/tasser_hand_drawn/item_shield.png' },
      // Keep freeze in the hand-drawn item namespace so it is shipped with the
      // other falling assets and does not depend on the legacy `bang` filename.
      { key: 'freeze', path: '/sprites/tasser_hand_drawn/item_freeze.png' },
    ];
    assets.forEach(({ key, path }) => {
      const img = new Image();
      img.src = assetUrl(path);
      this.handDrawnImages.set(key, img);
    });
    this.isHandDrawnLoaded = true;
  }

  // ─────────────────────────────────────────────────
  //  FALLING ITEMS
  // ─────────────────────────────────────────────────
  public static drawFallingItem(ctx: CanvasRenderingContext2D, item: FallingObject) {
    ctx.save();
    ctx.translate(item.x, item.y);
    ctx.rotate(item.rotation);

    // Drop shadow
    ctx.save();
    ctx.filter = 'blur(5px)';
    ctx.fillStyle = 'rgba(0,0,0,0.2)';
    ctx.beginPath();
    ctx.ellipse(3, 6, item.width * 0.36, item.height * 0.25, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.filter = 'none';
    ctx.restore();

    const w = item.width;
    const h = item.height;

    if (!this.isHandDrawnLoaded) this.initHandDrawnAssets();
    const handDrawnImg = this.handDrawnImages.get(item.type);

    if (handDrawnImg && handDrawnImg.complete && handDrawnImg.naturalWidth > 0) {
      // 🎨 Draw Tasser Hand-Drawn Sprite Image
      ctx.drawImage(handDrawnImg, -w / 2, -h / 2, w, h);
    } else if (item.type === 'fish_blue' || item.type === 'fish_pink' || item.type === 'fish_green') {
      const palette = {
        fish_blue:  { body: '#5C8EFF', body2: '#3D6EE8', belly: '#EFF6FF', line: '#2552C9', tail: '#4A7AE8', fin: '#7BB3FF' },
        fish_pink:  { body: '#FF7096', body2: '#E84B75', belly: '#FFF0F5', line: '#C43060', tail: '#F0537D', fin: '#FF9AB8' },
        fish_green: { body: '#3DD68C', body2: '#22B16B', belly: '#ECFDF5', line: '#0E8A4B', tail: '#2CC47A', fin: '#6DEDB8' },
      }[item.type];

      // Tail
      const tailGrad = ctx.createLinearGradient(-w * 0.55, 0, -w * 0.3, 0);
      tailGrad.addColorStop(0, 'transparent');
      tailGrad.addColorStop(1, palette.tail);
      ctx.fillStyle = tailGrad;
      ctx.beginPath();
      ctx.moveTo(-w * 0.38, 0);
      ctx.lineTo(-w * 0.62, -h * 0.4);
      ctx.lineTo(-w * 0.48, 0);
      ctx.lineTo(-w * 0.62, h * 0.4);
      ctx.closePath();
      ctx.fill();

      // Body glow
      const bodyGrad = ctx.createRadialGradient(-w * 0.05, -h * 0.12, 4, 0, 0, w * 0.45);
      bodyGrad.addColorStop(0, palette.fin);
      bodyGrad.addColorStop(0.5, palette.body);
      bodyGrad.addColorStop(1, palette.body2);
      ctx.fillStyle = bodyGrad;
      ctx.beginPath();
      ctx.ellipse(0, 0, w * 0.44, h * 0.42, 0, 0, Math.PI * 2);
      ctx.fill();

      // Top dorsal fin
      ctx.fillStyle = palette.fin;
      ctx.beginPath();
      ctx.moveTo(-w * 0.1, -h * 0.4);
      ctx.quadraticCurveTo(w * 0.05, -h * 0.62, w * 0.22, -h * 0.42);
      ctx.quadraticCurveTo(w * 0.1, -h * 0.38, -w * 0.1, -h * 0.4);
      ctx.fill();

      // Belly text region (bright, clean)
      const bellyGrad = ctx.createRadialGradient(w * 0.06, 0, 2, w * 0.06, 0, w * 0.3);
      bellyGrad.addColorStop(0, palette.belly);
      bellyGrad.addColorStop(1, 'rgba(255,255,255,0.4)');
      ctx.fillStyle = bellyGrad;
      ctx.beginPath();
      ctx.ellipse(w * 0.05, 0, w * 0.3, h * 0.28, 0, 0, Math.PI * 2);
      ctx.fill();

      // Outline stroke
      ctx.strokeStyle = palette.line;
      ctx.lineWidth = 2.5;
      ctx.beginPath();
      ctx.ellipse(0, 0, w * 0.44, h * 0.42, 0, 0, Math.PI * 2);
      ctx.stroke();

      // Eye
      ctx.fillStyle = '#1A1A2E';
      ctx.beginPath();
      ctx.arc(w * 0.28, -h * 0.08, 5, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = '#FFFFFF';
      ctx.beginPath();
      ctx.arc(w * 0.30, -h * 0.10, 2, 0, Math.PI * 2);
      ctx.fill();

      // Gleam highlight
      ctx.fillStyle = 'rgba(255,255,255,0.35)';
      ctx.beginPath();
      ctx.ellipse(-w * 0.12, -h * 0.22, w * 0.12, h * 0.08, -0.5, 0, Math.PI * 2);
      ctx.fill();

    } else if (item.type === 'paw_cookie') {
      // Warm cookie
      const cookGrad = ctx.createRadialGradient(0, 0, 4, 0, 0, w * 0.45);
      cookGrad.addColorStop(0, '#F5D78A');
      cookGrad.addColorStop(0.7, '#E8C26A');
      cookGrad.addColorStop(1, '#D4A843');
      ctx.fillStyle = cookGrad;
      ctx.beginPath();
      ctx.arc(0, 0, w * 0.43, 0, Math.PI * 2);
      ctx.fill();

      // Light inner cream for text
      ctx.fillStyle = 'rgba(255, 253, 220, 0.7)';
      ctx.beginPath();
      ctx.arc(0, 0, w * 0.3, 0, Math.PI * 2);
      ctx.fill();

      // Paw pads (top area, not in text region)
      ctx.fillStyle = '#F9A8D4';
      ctx.strokeStyle = '#D96E9E';
      ctx.lineWidth = 1;
      [[-w * 0.22, -h * 0.35], [-w * 0.06, -h * 0.42], [w * 0.06, -h * 0.42], [w * 0.22, -h * 0.35]].forEach(([px, py]) => {
        ctx.beginPath(); ctx.arc(px, py, 6.5, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
      });
      // Main pad
      ctx.fillStyle = '#F9A8D4';
      ctx.strokeStyle = '#D96E9E';
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.arc(0, h * 0.1, 10, 0, Math.PI * 2);
      ctx.fill(); ctx.stroke();

      // Cookie outline
      ctx.strokeStyle = '#C47A2A';
      ctx.lineWidth = 3;
      ctx.beginPath();
      ctx.arc(0, 0, w * 0.43, 0, Math.PI * 2);
      ctx.stroke();

      // Toasty edge gradient
      ctx.strokeStyle = '#8B5A2B';
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.arc(0, 0, w * 0.38, 0, Math.PI * 2);
      ctx.stroke();

    } else if (item.type === 'golden_fish') {
      // Golden gleaming fish bonus
      const gGrad = ctx.createRadialGradient(-w * 0.05, -h * 0.1, 4, 0, 0, w * 0.45);
      gGrad.addColorStop(0, '#FFE87C');
      gGrad.addColorStop(0.5, '#FFD700');
      gGrad.addColorStop(1, '#F59E0B');
      ctx.fillStyle = gGrad;
      ctx.beginPath();
      ctx.ellipse(0, 0, w * 0.44, h * 0.42, 0, 0, Math.PI * 2);
      ctx.fill();

      ctx.strokeStyle = '#D97706';
      ctx.lineWidth = 2.5;
      ctx.stroke();

      // Glow effect
      ctx.save();
      ctx.globalCompositeOperation = 'screen';
      const gGlow = ctx.createRadialGradient(0, 0, 0, 0, 0, w * 0.5);
      gGlow.addColorStop(0, 'rgba(255,240,100,0.3)');
      gGlow.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = gGlow;
      ctx.beginPath();
      ctx.arc(0, 0, w * 0.5, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();

      ctx.fillStyle = '#7C3500';
      ctx.font = 'bold 13px "Fredoka", sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('+50', 0, 0);

    } else if (item.type === 'clock') {
      // Clock bonus
      const clockGrad = ctx.createRadialGradient(-4, -6, 2, 0, 0, w * 0.4);
      clockGrad.addColorStop(0, '#67E8F9');
      clockGrad.addColorStop(1, '#0891B2');
      ctx.fillStyle = clockGrad;
      ctx.beginPath();
      ctx.arc(0, 0, w * 0.4, 0, Math.PI * 2);
      ctx.fill();

      ctx.fillStyle = '#FFFFFF';
      ctx.beginPath();
      ctx.arc(0, 0, w * 0.3, 0, Math.PI * 2);
      ctx.fill();

      // Clock hands
      ctx.strokeStyle = '#0E7490';
      ctx.lineWidth = 2.5;
      ctx.lineCap = 'round';
      ctx.beginPath();
      ctx.moveTo(0, 0); ctx.lineTo(0, -w * 0.18);
      ctx.moveTo(0, 0); ctx.lineTo(w * 0.14, w * 0.06);
      ctx.stroke();

      ctx.strokeStyle = '#0891B2';
      ctx.lineWidth = 2.5;
      ctx.beginPath();
      ctx.arc(0, 0, w * 0.4, 0, Math.PI * 2);
      ctx.stroke();

      ctx.fillStyle = '#155E75';
      ctx.font = 'bold 11px "Fredoka", sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('+5s', 0, w * 0.15);

    } else if (item.type === 'shield') {
      const shGrad = ctx.createRadialGradient(-5, -8, 2, 0, 0, w * 0.4);
      shGrad.addColorStop(0, '#6EE7B7');
      shGrad.addColorStop(1, '#059669');
      ctx.fillStyle = shGrad;
      ctx.beginPath();
      ctx.arc(0, 0, w * 0.4, 0, Math.PI * 2);
      ctx.fill();

      ctx.strokeStyle = '#065F46';
      ctx.lineWidth = 2.5;
      ctx.stroke();

      ctx.fillStyle = '#D1FAE5';
      ctx.strokeStyle = '#064E3B';
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(0, -w * 0.24);
      ctx.lineTo(w * 0.19, -w * 0.12);
      ctx.lineTo(w * 0.15, w * 0.14);
      ctx.lineTo(0, w * 0.25);
      ctx.lineTo(-w * 0.15, w * 0.14);
      ctx.lineTo(-w * 0.19, -w * 0.12);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();

    } else if (item.type === 'freeze') {
      // Ice Freeze Bonus
      const frGrad = ctx.createRadialGradient(-5, -8, 2, 0, 0, w * 0.42);
      frGrad.addColorStop(0, '#E0F2FE');
      frGrad.addColorStop(0.5, '#38BDF8');
      frGrad.addColorStop(1, '#0284C7');
      ctx.fillStyle = frGrad;
      ctx.beginPath();
      ctx.arc(0, 0, w * 0.42, 0, Math.PI * 2);
      ctx.fill();

      ctx.strokeStyle = '#0369A1';
      ctx.lineWidth = 2.5;
      ctx.stroke();

      ctx.strokeStyle = '#E0F2FE';
      ctx.lineWidth = 2;
      ctx.lineCap = 'round';
      for (let i = 0; i < 3; i++) {
        const angle = (Math.PI / 3) * i;
        const dx = Math.cos(angle) * w * 0.22;
        const dy = Math.sin(angle) * w * 0.22;
        ctx.beginPath();
        ctx.moveTo(-dx, -dy);
        ctx.lineTo(dx, dy);
        ctx.stroke();
      }

      ctx.fillStyle = '#0C4A6E';
      ctx.font = 'bold 11px "Fredoka", sans-serif';
      ctx.fillText('ĐÓNG BĂNG 2s', 0, w * 0.22);
    }

    // ─ Render Answer Text (non-bonus) ─
    // Long meanings shrink and wrap onto two lines so they never spill off the card.
    if (!item.isBonus && item.text) {
      ctx.save();
      ctx.rotate(-item.rotation);

      const layout = layoutCardText(ctx, item.text, item.width * 0.74, item.height * 0.7, item.japanese);
      ctx.font = layout.font;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.lineJoin = 'round';
      ctx.lineWidth = 3;
      ctx.strokeStyle = 'rgba(255,255,255,0.9)';
      ctx.fillStyle = '#111827';

      const top = -((layout.lines.length - 1) * layout.lineHeight) / 2;
      layout.lines.forEach((line, index) => {
        const y = top + index * layout.lineHeight;
        // A slim light halo keeps Japanese/Vietnamese labels readable on every card colour.
        ctx.strokeText(line, 0, y);
        ctx.fillText(line, 0, y);
      });

      ctx.restore();
    }

    ctx.restore();
  }
}
