interface Frame {
  x: number; y: number; width: number; height: number;
  centerX?: number; centerY?: number; scaleHeight?: number;
}
interface PreviewBundle {
  body: { path: string };
  effects: { path: string; frames: Frame[] };
  actions: { idle: { frameDurationMs: number; sourceFrames: Frame[] } };
}

const images = new Map<string, Promise<HTMLImageElement>>();
function loadImage(url: string): Promise<HTMLImageElement> {
  let pending = images.get(url);
  if (!pending) {
    pending = new Promise<HTMLImageElement>((resolve, reject) => {
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = () => { images.delete(url); reject(new Error("Không tải được ảnh máy bay")); };
      img.src = url;
    });
    images.set(url, pending);
  }
  return pending;
}

/** All visible skins loop their idle frames and exhaust, including unselected skins. */
export function animatePlanePreview(canvas: HTMLCanvasElement, bundle: PreviewBundle | null,
  fallbackImage: string, assetBase: string, onReady: () => void, onError: () => void): () => void {
  let alive = true;
  let raf = 0;
  let draw: ((time: number) => void) | null = null;
  const schedule = () => {
    if (alive && draw && !document.hidden && !raf) raf = requestAnimationFrame(draw);
  };
  const visibility = () => {
    cancelAnimationFrame(raf);
    raf = 0;
    schedule();
  };
  document.addEventListener("visibilitychange", visibility);

  const body = loadImage(bundle ? `${assetBase}${bundle.body.path}` : fallbackImage);
  const flames = bundle
    ? Promise.all([loadImage(`${assetBase}${bundle.effects.path}`)])
    : Promise.all([1, 2, 3, 4].map((i) => loadImage(`${assetBase}assets/space/FX/exhaust_0${i}.png`)));
  void Promise.all([body, flames.catch(() => [])]).then(([hull, exhaust]) => {
    const ctx = canvas.getContext("2d");
    if (!alive || !ctx) return;
    const idle = bundle?.actions.idle;
    let ready = false;
    draw = (time) => {
      raf = 0;
      if (!alive || document.hidden) return;
      const bob = Math.sin(time / 600) * 3;
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      if (exhaust.length) {
        const flameIndex = Math.floor(time / 90) % (bundle ? bundle.effects.frames.length : exhaust.length);
        const flame = bundle?.effects.frames[flameIndex];
        const image = bundle ? exhaust[0] : exhaust[flameIndex];
        const rect = flame ?? { x: 0, y: 0, width: image.naturalWidth, height: image.naturalHeight };
        const scale = canvas.height * .27 / rect.height;
        ctx.drawImage(image, rect.x, rect.y, rect.width, rect.height,
          canvas.width / 2 - (rect.centerX ?? rect.width / 2) * scale,
          canvas.height * (bundle ? .62 : .72) + bob - (rect.centerY ?? rect.height / 2) * scale,
          rect.width * scale, rect.height * scale);
      }
      const frame = idle?.sourceFrames[Math.floor(time / idle.frameDurationMs) % idle.sourceFrames.length]
        ?? { x: 0, y: 0, width: hull.naturalWidth, height: hull.naturalHeight };
      const scale = Math.min(canvas.width * .84 / frame.width, canvas.height * .72 / (frame.scaleHeight ?? frame.height));
      ctx.drawImage(hull, frame.x, frame.y, frame.width, frame.height,
        canvas.width / 2 - (frame.centerX ?? frame.width / 2) * scale,
        canvas.height * (bundle ? .5 : .45) + bob - (frame.centerY ?? frame.height / 2) * scale,
        frame.width * scale, frame.height * scale);
      if (!ready) { ready = true; onReady(); }
      schedule();
    };
    schedule();
  }).catch(() => { if (alive) onError(); });

  return () => {
    alive = false;
    cancelAnimationFrame(raf);
    document.removeEventListener("visibilitychange", visibility);
  };
}
