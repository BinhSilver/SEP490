/**
 * Scoped Hero Video & Particle Canvas Controller
 * Manages video playback & interactive glowing particles scoped to Hero Section div.
 */
(function () {
  'use strict';

  function initHeroBackground() {
    const video = document.querySelector('.v0-sakura-hero-bg video');
    if (video) {
      video.play().catch(function (e) { console.log('Tự động phát bị trì hoãn:', e); });
    }

    const canvas = document.getElementById('hero-particle-canvas');
    if (canvas) {
      setupInteractiveParticles(canvas);
    }
  }

  function setupInteractiveParticles(canvas) {
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let width = 0;
    let height = 0;
    const mouse = { x: 0.5, y: 0.5 };

    function resize() {
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const parent = canvas.parentElement || document.body;
      width = parent.clientWidth || window.innerWidth;
      height = parent.clientHeight || window.innerHeight;
      canvas.width = width * dpr;
      canvas.height = height * dpr;
      ctx.scale(dpr, dpr);
    }
    resize();
    window.addEventListener('resize', resize);

    window.addEventListener('mousemove', (e) => {
      mouse.x = e.clientX / window.innerWidth;
      mouse.y = e.clientY / window.innerHeight;
    });

    const particles = Array.from({ length: 70 }, (_, i) => {
      const r = 1.618 * i;
      return {
        bx: (127.1 * r) % 1,
        by: (311.7 * r) % 1,
        phase: r * Math.PI * 2,
        speed: 0.4 + (r % 0.4),
        radius: 1.2 + (r % 2.2)
      };
    });

    let time = 0;
    function animate() {
      ctx.clearRect(0, 0, width, height);

      particles.forEach((p) => {
        const waveX = 38 * Math.sin(time * p.speed * 0.4 + p.phase);
        const waveY = 24 * Math.cos(time * p.speed * 0.3 + 0.7 * p.phase);

        const px = p.bx * width;
        const py = p.by * height;

        const dx = p.bx - mouse.x;
        const dy = p.by - mouse.y;

        const force = Math.max(0, 1 - 2.8 * Math.sqrt(dx * dx + dy * dy));

        const finalX = px + waveX + force * Math.cos(time + p.phase) * 36;
        const finalY = py + waveY + force * Math.sin(time + p.phase) * 36;
        const opacityScale = 0.5 * Math.sin(time * p.speed + p.phase) + 0.5;

        ctx.beginPath();
        ctx.arc(finalX, finalY, p.radius + 0.8 * opacityScale, 0, 2 * Math.PI);
        ctx.fillStyle = `rgba(255, 183, 197, ${0.12 + 0.22 * opacityScale + 0.3 * force})`;
        ctx.fill();
      });

      time += 0.016;
      requestAnimationFrame(animate);
    }

    animate();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initHeroBackground);
  } else {
    initHeroBackground();
  }
})();
