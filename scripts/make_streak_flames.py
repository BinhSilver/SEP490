"""Cuts fire.png into 4 flame sprites, removes the baked-in numbers, turns the dark background transparent."""
import os
import cv2
import numpy as np
from PIL import Image

SRC = r'D:\Study\cursur\feat-all - Copy\update-w0127\fire.png'
OUT = os.path.dirname(os.path.abspath(__file__))
# Panel boxes (x0, y0, x1, y1) measured on the 1672x941 source, inset a little from the rounded borders.
PANELS = [(32, 114, 367, 802), (397, 114, 724, 802), (754, 114, 1149, 802), (1178, 114, 1640, 802)]
DIGITS = [(140, 505, 252, 638), (503, 488, 624, 636), (855, 462, 1060, 628), (1282, 455, 1525, 622)]

img = cv2.imread(SRC, cv2.IMREAD_COLOR)  # BGR
for index, (x0, y0, x1, y1) in enumerate(PANELS, start=1):
    panel = img[y0:y1, x0:x1].copy()
    h, w = panel.shape[:2]
    bg = np.median(panel[5:30, 5:30].reshape(-1, 3), axis=0)

    # --- 1. mask the baked number (boxes measured on the source, then rounded and grown)
    gx0, gy0, gx1, gy1 = DIGITS[index - 1]
    mask = np.zeros((h, w), np.uint8)
    cv2.rectangle(mask, (gx0 - x0, gy0 - y0), (gx1 - x0, gy1 - y0), 255, thickness=cv2.FILLED)
    mask = cv2.dilate(mask, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (17, 17)))
    cv2.imwrite(os.path.join(OUT, f'mask-{index}.png'), mask)

    # --- 2. paint the number away
    clean = cv2.inpaint(panel, mask, 18, cv2.INPAINT_TELEA)
    soft = cv2.GaussianBlur(clean, (0, 0), 14)
    blend = (cv2.GaussianBlur(mask, (0, 0), 10).astype(np.float32) / 255.0)[:, :, None]
    clean = (clean * (1 - blend) + soft * blend).astype(np.uint8)

    # --- 3. colour-to-alpha against the dark background (keeps the glow)
    c = clean.astype(np.float32)
    b = bg.astype(np.float32)[None, None, :]
    alpha0 = np.max(np.clip((c - b) / (255.0 - b), 0, 1), axis=2)
    safe = np.maximum(alpha0, 1e-3)[:, :, None]
    color = np.clip(b + (c - b) / safe, 0, 255)  # un-mix the background using the true coverage
    # then drop the faint haze and fade out towards the panel edges, so no straight edge shows
    alpha = np.clip((alpha0 - 0.10) / 0.90, 0, 1)
    yy, xx = np.mgrid[0:h, 0:w]
    edge = np.minimum.reduce([xx, w - 1 - xx, yy, h - 1 - yy]).astype(np.float32)
    alpha *= np.clip(edge / 40.0, 0, 1)
    rgba = np.dstack([color[:, :, ::-1], alpha * 255]).astype(np.uint8)  # to RGB + A

    pil = Image.fromarray(rgba, 'RGBA')
    box = pil.getchannel('A').point(lambda v: 255 if v > 12 else 0).getbbox()
    pil = pil.crop((max(0, box[0] - 10), max(0, box[1] - 10), min(w, box[2] + 10), min(h, box[3] + 10)))
    pil.thumbnail((440, 440), Image.LANCZOS)
    pil.save(os.path.join(OUT, f'flame-{index}.webp'), 'WEBP', quality=90, method=6)
    pil.save(os.path.join(OUT, f'flame-{index}.png'))
    print(index, pil.size, os.path.getsize(os.path.join(OUT, f'flame-{index}.webp')))

# preview on the card colour
sheet = Image.new('RGB', (4 * 460, 480), (255, 253, 248))
for i in range(4):
    f = Image.open(os.path.join(OUT, f'flame-{i + 1}.png'))
    sheet.paste(f, (i * 460 + (460 - f.width) // 2, 470 - f.height), f)
sheet.save(os.path.join(OUT, 'preview.png'))
