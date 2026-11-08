import { assetUrl } from '../assets';

const images = new Map<string, HTMLImageElement>();
const pending = new Map<HTMLImageElement, Promise<HTMLImageElement>>();
const failedImages = new WeakSet<HTMLImageElement>();

export function spriteImage(path: string): HTMLImageElement {
  const url = assetUrl(path);
  let image = images.get(url);
  if (!image) {
    image = new Image();
    images.set(url, image);
    image.src = url;
  }
  return image;
}

/** A failed request is retried explicitly; drawing never launches a retry every frame. */
export function prepareImage(path: string): Promise<HTMLImageElement> {
  const url = assetUrl(path);
  let image = spriteImage(path);
  if (failedImages.has(image) || (image.complete && !image.naturalWidth)) {
    images.delete(url);
    image = spriteImage(path);
  }
  const existing = pending.get(image);
  if (existing) return existing;
  const target = image;
  const ready = new Promise<HTMLImageElement>((resolve, reject) => {
    let settled = false;
    const cleanup = () => {
      window.clearTimeout(timeout);
      target.removeEventListener('load', loaded);
      target.removeEventListener('error', failed);
    };
    const failed = () => {
      if (settled) return;
      settled = true;
      cleanup();
      failedImages.add(target);
      reject(new Error('Không tải được hình trò chơi. Kiểm tra mạng và thử lại.'));
    };
    const loaded = async () => {
      try {
        if (target.decode) await target.decode();
        if (!target.naturalWidth) throw new Error('Empty image');
        if (settled) return;
        settled = true;
        cleanup();
        resolve(target);
      } catch { failed(); }
    };
    const timeout = window.setTimeout(failed, 45000);
    target.addEventListener('load', loaded, { once: true });
    target.addEventListener('error', failed, { once: true });
    if (target.complete) { if (target.naturalWidth) void loaded(); else failed(); }
  });
  pending.set(target, ready);
  void ready.catch(() => pending.delete(target));
  return ready;
}
