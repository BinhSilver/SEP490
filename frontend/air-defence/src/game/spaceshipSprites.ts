import {Assets, Rectangle, Sprite, Texture} from 'pixi.js';
import manifests from './spaceship-manifests.json';
export type SpaceshipName = keyof typeof manifests;
export const SPACESHIP_NAMES = Object.keys(manifests) as SpaceshipName[];
export function spaceshipName(code: string | null | undefined): SpaceshipName | null {
  const name = code?.replace(/^plane-/, '');
  return name && Object.prototype.hasOwnProperty.call(manifests, name) ? name as SpaceshipName : null;
}
export interface SpaceshipTextures { body: Texture[][]; effects: Texture[][]; color: number }
export type SpaceshipBundle = (typeof manifests)[SpaceshipName];

/**
 * Gói skin admin tải lên (render code PACK): manifest.json cùng định dạng máy bay gốc, đường dẫn ảnh tương đối trong
 * thư mục gói. Mã máy bay (vd. plane-moi) -> thư mục gói.
 */
const packPaths = new Map<string, string>();
const packLoads = new Map<string, Promise<SpaceshipBundle | null>>();
export function registerSpaceshipPack(code: string, packPath: string) {
  packPaths.set(code, packPath.endsWith('/') ? packPath : `${packPath}/`);
}
/** Manifest + thư mục ảnh của máy bay: gói tải lên hoặc máy bay gốc; null nếu không có bộ ảnh động. */
export async function spaceshipSource(code: string): Promise<{ bundle: SpaceshipBundle; base: string } | null> {
  const pack = packPaths.get(code);
  if (pack) {
    let pending = packLoads.get(code);
    if (!pending) {
      pending = fetch(`${pack}manifest.json`)
        .then(response => (response.ok ? response.json() as Promise<SpaceshipBundle> : null))
        .catch(() => null);
      packLoads.set(code, pending);
    }
    const bundle = await pending;
    return bundle ? { bundle, base: pack } : null;
  }
  const name = spaceshipName(code);
  return name ? { bundle: manifests[name], base: import.meta.env.BASE_URL } : null;
}
/** Each generated pose has its own hull axis; keep that axis on the player's position. */
export function setSpaceshipFrame(sprite: Sprite, texture: Texture) {
  sprite.texture = texture;
  const anchor = texture.defaultAnchor;
  sprite.anchor.set(anchor?.x ?? 0.5, anchor?.y ?? 0.5);
}
export async function loadSpaceship(code: string): Promise<SpaceshipTextures | null> {
  const source = await spaceshipSource(code);
  if (!source) return null;
  const {bundle, base} = source;
  const sheets = await Promise.all([bundle.body, bundle.effects].map(async config => {
    const sheet = await Assets.load<Texture>(`${base}${config.path.replace(/^\/+/, '')}`);
    sheet.source.style.scaleMode = 'nearest';
    return config.frames.map(frame => new Texture({source: sheet.source,
      frame: new Rectangle(frame.x, frame.y, frame.width, frame.height),
      defaultAnchor: {x: frame.centerX / frame.width, y: frame.centerY / frame.height}}));
  }));
  const rows = (frames: Texture[]) => Array.from({length: 4}, (_, i) => frames.slice(i * 4, i * 4 + 4));
  return {body: rows(sheets[0]), effects: rows(sheets[1]), color: parseInt(bundle.color.slice(1), 16)};
}
