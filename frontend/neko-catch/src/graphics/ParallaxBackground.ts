import { assetUrl } from '../assets';

/**
 * Stage background: the hand-drawn panoramic composite with drifting sakura petals. While the image
 * downloads (or if it fails) the original vector "Neko Bakery" scene is drawn instead.
 */
export class ParallaxBackground {
  private cloudX = 0;
  private cloud2X = 600;
  private cloud3X = 1100;

  private composite: HTMLImageElement | null = null;

  // Sakura petals for the hand-drawn stage
  private petals: { x: number; y: number; size: number; speedX: number; speedY: number; rot: number; rotSpeed: number }[] = [];

  constructor() {
    this.initImages();
    this.initPetals();
  }

  private initPetals() {
    this.petals = Array.from({ length: 18 }, () => ({
      x: Math.random() * 1600,
      y: Math.random() * 900,
      size: Math.random() * 8 + 6,
      speedX: Math.random() * 1.2 + 0.6,
      speedY: Math.random() * 1.5 + 1.0,
      rot: Math.random() * Math.PI * 2,
      rotSpeed: (Math.random() - 0.5) * 0.04,
    }));
  }

  private initImages() {
    if (typeof window === 'undefined') return;
    const img = new Image();
    img.src = assetUrl('/sprites/tasser_hand_drawn/bg_panoramic_composite.png');
    this.composite = img;
  }

  public draw(ctx: CanvasRenderingContext2D, width: number, height: number, time: number) {
    const GROUND = height - 110;

    const imgComp = this.composite;
    if (imgComp && imgComp.complete && imgComp.naturalWidth > 0) {
      ctx.drawImage(imgComp, 0, 0, width, height);
      this.drawPetals(ctx, width, height);
      return;
    }

    // ── LAYER 1: Deep sky gradient (dusk / sunset vibes) ──
    const skyGrad = ctx.createLinearGradient(0, 0, 0, GROUND);
    skyGrad.addColorStop(0,   '#0D1B3E');   // deep navy
    skyGrad.addColorStop(0.3, '#1A3A6B');   // cobalt
    skyGrad.addColorStop(0.7, '#3B7EC8');   // sky blue
    skyGrad.addColorStop(1,   '#A8D5F2');   // horizon haze
    ctx.fillStyle = skyGrad;
    ctx.fillRect(0, 0, width, GROUND);

    // ── LAYER 2: Glowing moon / sun ──
    const moonX = width * 0.82;
    const moonY = height * 0.14;
    const moonR = 42;

    // Outer aura
    const moonAura = ctx.createRadialGradient(moonX, moonY, 0, moonX, moonY, moonR * 2.5);
    moonAura.addColorStop(0,   'rgba(255, 255, 210, 0.18)');
    moonAura.addColorStop(0.5, 'rgba(255, 230, 150, 0.07)');
    moonAura.addColorStop(1,   'rgba(0,0,0,0)');
    ctx.fillStyle = moonAura;
    ctx.beginPath();
    ctx.arc(moonX, moonY, moonR * 2.5, 0, Math.PI * 2);
    ctx.fill();

    // Moon body
    const moonBody = ctx.createRadialGradient(moonX - 10, moonY - 10, 2, moonX, moonY, moonR);
    moonBody.addColorStop(0,   '#FFFDE7');
    moonBody.addColorStop(0.6, '#FFF59D');
    moonBody.addColorStop(1,   '#F9A825');
    ctx.fillStyle = moonBody;
    ctx.beginPath();
    ctx.arc(moonX, moonY, moonR, 0, Math.PI * 2);
    ctx.fill();

    // ── LAYER 3: Stars ──
    ctx.save();
    const starData = [
      [80, 40], [150, 25], [250, 60], [320, 18], [450, 45], [540, 30],
      [620, 55], [680, 20], [760, 48], [820, 65], [900, 32], [960, 50],
      [35, 80], [180, 90], [370, 72], [490, 85], [650, 96], [750, 78], [870, 88]
    ];
    starData.forEach(([x, y]) => {
      const twinkle = 0.4 + 0.6 * Math.abs(Math.sin(time * 0.002 + x * 0.05 + y * 0.07));
      ctx.globalAlpha = twinkle;
      ctx.fillStyle = '#FFFFFF';
      ctx.beginPath();
      ctx.arc(x, y, 1.2, 0, Math.PI * 2);
      ctx.fill();
    });
    ctx.globalAlpha = 1;
    ctx.restore();

    // ── LAYER 4: Distant mountains silhouette ──
    ctx.fillStyle = '#0A2346';
    ctx.beginPath();
    ctx.moveTo(0, GROUND - 40);
    ctx.lineTo(width * 0.12, GROUND - 130);
    ctx.lineTo(width * 0.22, GROUND - 80);
    ctx.lineTo(width * 0.35, GROUND - 160);
    ctx.lineTo(width * 0.52, GROUND - 90);
    ctx.lineTo(width * 0.65, GROUND - 175);
    ctx.lineTo(width * 0.80, GROUND - 100);
    ctx.lineTo(width * 0.90, GROUND - 145);
    ctx.lineTo(width, GROUND - 80);
    ctx.lineTo(width, GROUND);
    ctx.lineTo(0, GROUND);
    ctx.closePath();
    ctx.fill();

    // ── LAYER 5: Mid-range hills with slight glow ──
    const hillGrad = ctx.createLinearGradient(0, GROUND - 80, 0, GROUND);
    hillGrad.addColorStop(0, '#0E3D66');
    hillGrad.addColorStop(1, '#081A30');
    ctx.fillStyle = hillGrad;
    ctx.beginPath();
    ctx.moveTo(0, GROUND);
    ctx.lineTo(0, GROUND - 50);
    ctx.bezierCurveTo(width * 0.15, GROUND - 90, width * 0.30, GROUND - 50, width * 0.45, GROUND - 70);
    ctx.bezierCurveTo(width * 0.60, GROUND - 90, width * 0.75, GROUND - 40, width, GROUND - 60);
    ctx.lineTo(width, GROUND);
    ctx.closePath();
    ctx.fill();

    // ── LAYER 6: Animated clouds ──
    this.cloudX  = ((this.cloudX  + 0.25) % (width + 260)) - 130;
    this.cloud2X = ((this.cloud2X + 0.40) % (width + 300)) - 150;
    this.cloud3X = ((this.cloud3X + 0.18) % (width + 340)) - 170;

    this.drawCloud(ctx, this.cloudX,  GROUND * 0.28, 1.1, 0.55);
    this.drawCloud(ctx, this.cloud2X, GROUND * 0.18, 0.85, 0.45);
    this.drawCloud(ctx, this.cloud3X, GROUND * 0.38, 1.3, 0.4);

    // ── LAYER 7: Left - Neko Bakery Building ──
    this.drawBakery(ctx, 18, GROUND - 170);

    // ── LAYER 8: Right - Garden / Tree ──
    this.drawGarden(ctx, width - 160, GROUND - 160);

    // ── LAYER 9: Ground Platform ──
    // Ground glow strip
    ctx.fillStyle = 'rgba(100, 220, 255, 0.08)';
    ctx.fillRect(0, GROUND - 2, width, 6);

    // Ground base
    const groundGrad = ctx.createLinearGradient(0, GROUND, 0, height);
    groundGrad.addColorStop(0,   '#3A5A40');
    groundGrad.addColorStop(0.12,'#2D4A2E');
    groundGrad.addColorStop(0.13,'#8B7355');
    groundGrad.addColorStop(1,   '#5C4A30');
    ctx.fillStyle = groundGrad;
    ctx.fillRect(0, GROUND, width, height - GROUND);

    // Grass top edge
    ctx.fillStyle = '#4CAF50';
    ctx.fillRect(0, GROUND, width, 8);
    ctx.fillStyle = '#66BB6A';
    ctx.fillRect(0, GROUND, width, 3);

    // Stone tiles on ground
    ctx.strokeStyle = 'rgba(255,255,255,0.06)';
    ctx.lineWidth = 1;
    for (let tx = 0; tx < width; tx += 48) {
      ctx.beginPath();
      ctx.moveTo(tx, GROUND + 10);
      ctx.lineTo(tx, height);
      ctx.stroke();
    }
    for (let ty = GROUND + 18; ty < height; ty += 20) {
      ctx.beginPath();
      ctx.moveTo(0, ty);
      ctx.lineTo(width, ty);
      ctx.stroke();
    }

    // Ground ambient glow at feet level
    const footGlow = ctx.createLinearGradient(0, GROUND - 4, 0, GROUND + 30);
    footGlow.addColorStop(0, 'rgba(120, 255, 200, 0.12)');
    footGlow.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = footGlow;
    ctx.fillRect(0, GROUND - 4, width, 34);
  }

  private drawCloud(ctx: CanvasRenderingContext2D, x: number, y: number, scale: number, alpha: number) {
    ctx.save();
    ctx.translate(x, y);
    ctx.scale(scale, scale);
    ctx.globalAlpha = alpha;
    ctx.fillStyle = '#FFFFFF';

    // Soft multi-bubble cloud
    const bubbles = [
      [0, 0, 28], [28, -12, 36], [64, -6, 28], [88, 4, 22],
      [-18, 6, 22], [44, 10, 20]
    ];
    bubbles.forEach(([bx, by, r]) => {
      ctx.beginPath();
      ctx.arc(bx, by, r, 0, Math.PI * 2);
      ctx.fill();
    });
    ctx.restore();
  }

  private drawBakery(ctx: CanvasRenderingContext2D, x: number, y: number) {
    // Main building body
    const bGrad = ctx.createLinearGradient(x, y, x, y + 170);
    bGrad.addColorStop(0, '#3D1D7A');
    bGrad.addColorStop(1, '#1F0F40');
    ctx.fillStyle = bGrad;
    ctx.fillRect(x, y + 28, 130, 142);

    // Roof
    ctx.fillStyle = '#7C3AED';
    ctx.beginPath();
    ctx.moveTo(x - 8,  y + 32);
    ctx.lineTo(x + 65, y - 2);
    ctx.lineTo(x + 138, y + 32);
    ctx.closePath();
    ctx.fill();

    // Roof highlight
    ctx.strokeStyle = '#A78BFA';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(x - 8, y + 32);
    ctx.lineTo(x + 65, y - 2);
    ctx.lineTo(x + 138, y + 32);
    ctx.stroke();

    // Sign board
    ctx.fillStyle = '#FDE68A';
    ctx.beginPath();
    ctx.roundRect(x + 14, y + 48, 102, 32, 6);
    ctx.fill();

    ctx.fillStyle = '#92400E';
    ctx.font = 'bold 12px "Fredoka", sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('NEKO BAKERY', x + 65, y + 69);

    // Windows
    [[x+22, y+96], [x+82, y+96]].forEach(([wx, wy]) => {
      ctx.fillStyle = '#FDE68A';
      ctx.beginPath();
      ctx.roundRect(wx, wy, 30, 36, 4);
      ctx.fill();
      ctx.fillStyle = 'rgba(255,210,50,0.3)';
      ctx.fillRect(wx, wy, 30, 36);
      ctx.strokeStyle = '#5B21B6';
      ctx.lineWidth = 2;
      ctx.strokeRect(wx, wy, 30, 36);
      // Cross
      ctx.strokeStyle = '#5B21B6';
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.moveTo(wx + 15, wy); ctx.lineTo(wx + 15, wy + 36);
      ctx.moveTo(wx, wy + 18); ctx.lineTo(wx + 30, wy + 18);
      ctx.stroke();
    });

    // Door
    ctx.fillStyle = '#5B21B6';
    ctx.beginPath();
    ctx.roundRect(x + 46, y + 128, 38, 42, [6, 6, 0, 0]);
    ctx.fill();
    ctx.fillStyle = '#F5F3FF';
    ctx.beginPath();
    ctx.arc(x + 78, y + 150, 3, 0, Math.PI * 2);
    ctx.fill();
  }

  private drawGarden(ctx: CanvasRenderingContext2D, x: number, y: number) {
    // Large tree
    const canopyColors = ['#1B5E20', '#2E7D32', '#388E3C', '#43A047'];
    [[x + 70, y, 55], [x + 45, y + 22, 45], [x + 95, y + 18, 50]].forEach(([cx, cy, r], i) => {
      ctx.fillStyle = canopyColors[i % canopyColors.length];
      ctx.beginPath();
      ctx.arc(cx, cy, r, 0, Math.PI * 2);
      ctx.fill();
    });

    // Tree trunk
    const trunkGrad = ctx.createLinearGradient(x + 60, y + 50, x + 90, y + 50);
    trunkGrad.addColorStop(0, '#6D4C41');
    trunkGrad.addColorStop(1, '#4E342E');
    ctx.fillStyle = trunkGrad;
    ctx.fillRect(x + 60, y + 44, 26, 126);

    // Small fence
    ctx.strokeStyle = '#A1A1AA';
    ctx.lineWidth = 2;
    for (let fx = x - 50; fx < x + 130; fx += 20) {
      ctx.fillStyle = '#6366F1';
      ctx.fillRect(fx, y + 100, 8, 70);
    }
    ctx.fillStyle = 'rgba(0,0,0,0)';
    ctx.strokeStyle = '#818CF8';
    ctx.lineWidth = 3;
    ctx.beginPath();
    ctx.moveTo(x - 50, y + 110);
    ctx.lineTo(x + 130, y + 110);
    ctx.stroke();

    // Cute small flowers
    [[x - 30, y + 155], [x - 10, y + 150], [x + 10, y + 158], [x + 30, y + 153]].forEach(([fx, fy]) => {
      ctx.fillStyle = '#EC4899';
      ctx.beginPath();
      ctx.arc(fx, fy, 5, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = '#FDE68A';
      ctx.beginPath();
      ctx.arc(fx, fy, 2.5, 0, Math.PI * 2);
      ctx.fill();
    });
  }

  private drawPetals(ctx: CanvasRenderingContext2D, width: number, height: number) {
    ctx.save();
    this.petals.forEach((p) => {
      p.x += p.speedX;
      p.y += p.speedY;
      p.rot += p.rotSpeed;

      if (p.x > width + 20) p.x = -20;
      if (p.y > height + 20) p.y = -20;

      ctx.save();
      ctx.translate(p.x, p.y);
      ctx.rotate(p.rot);
      ctx.fillStyle = 'rgba(255, 182, 193, 0.75)';
      ctx.beginPath();
      ctx.ellipse(0, 0, p.size, p.size * 0.5, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
    });
    ctx.restore();
  }
}
