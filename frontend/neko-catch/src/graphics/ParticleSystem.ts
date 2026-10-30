export interface Particle {
  x: number;
  y: number;
  vx: number;
  vy: number;
  size: number;
  color: string;
  alpha: number;
  life: number;
  maxLife: number;
  type: 'sparkle' | 'paw' | 'star' | 'smoke' | 'confetti';
}

export class ParticleSystem {
  private particles: Particle[] = [];

  public emitCorrect(x: number, y: number) {
    const colors = ['#FFD700', '#FFA500', '#FF69B4', '#00FF7F', '#1E90FF'];
    for (let i = 0; i < 24; i++) {
      const angle = Math.random() * Math.PI * 2;
      const speed = 3 + Math.random() * 6;
      this.particles.push({
        x,
        y,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed - 2,
        size: 4 + Math.random() * 8,
        color: colors[Math.floor(Math.random() * colors.length)],
        alpha: 1,
        life: 0,
        maxLife: 30 + Math.random() * 20,
        type: i % 3 === 0 ? 'paw' : 'sparkle'
      });
    }
  }

  public emitWrong(x: number, y: number) {
    for (let i = 0; i < 16; i++) {
      const angle = Math.random() * Math.PI * 2;
      const speed = 2 + Math.random() * 4;
      this.particles.push({
        x,
        y,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed,
        size: 8 + Math.random() * 12,
        color: '#A4B0BE',
        alpha: 0.8,
        life: 0,
        maxLife: 25 + Math.random() * 15,
        type: 'smoke'
      });
    }
  }

  public emitCombo(x: number, y: number) {
    const colors = ['#FF4757', '#FFA502', '#2ED573', '#1E90FF', '#9B59B6'];
    for (let i = 0; i < 30; i++) {
      const angle = Math.random() * Math.PI * 2;
      const speed = 4 + Math.random() * 8;
      this.particles.push({
        x,
        y,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed - 4,
        size: 6 + Math.random() * 10,
        color: colors[Math.floor(Math.random() * colors.length)],
        alpha: 1,
        life: 0,
        maxLife: 40 + Math.random() * 20,
        type: 'star'
      });
    }
  }

  public updateAndDraw(ctx: CanvasRenderingContext2D, visualScale = 1) {
    for (let i = this.particles.length - 1; i >= 0; i--) {
      const p = this.particles[i];
      p.life++;
      p.x += p.vx * visualScale;
      p.y += p.vy * visualScale;
      p.vy += 0.15; // Gravity
      p.alpha = 1 - (p.life / p.maxLife);

      if (p.life >= p.maxLife || p.alpha <= 0) {
        this.particles.splice(i, 1);
        continue;
      }

      ctx.save();
      ctx.globalAlpha = Math.max(0, p.alpha);
      ctx.translate(p.x, p.y);
      ctx.scale(visualScale, visualScale);
      ctx.translate(-p.x, -p.y);

      if (p.type === 'sparkle' || p.type === 'star') {
        ctx.fillStyle = p.color;
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2);
        ctx.fill();
      } else if (p.type === 'paw') {
        ctx.fillStyle = p.color;
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2);
        ctx.arc(p.x - p.size * 0.6, p.y - p.size * 0.7, p.size * 0.4, 0, Math.PI * 2);
        ctx.arc(p.x, p.y - p.size * 0.9, p.size * 0.4, 0, Math.PI * 2);
        ctx.arc(p.x + p.size * 0.6, p.y - p.size * 0.7, p.size * 0.4, 0, Math.PI * 2);
        ctx.fill();
      } else if (p.type === 'smoke') {
        ctx.fillStyle = p.color;
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2);
        ctx.fill();
      }

      ctx.restore();
    }
  }
}
