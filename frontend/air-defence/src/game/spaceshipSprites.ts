import {Assets, Rectangle, Sprite, Texture} from 'pixi.js';
import manifests from './spaceship-manifests.json';
export type SpaceshipName = keyof typeof manifests;
export const SPACESHIP_NAMES = Object.keys(manifests) as SpaceshipName[];
export function spaceshipName(code: string | null | undefined): SpaceshipName | null {
  const name = code?.replace(/^plane-/, '');
  return name && Object.prototype.hasOwnProperty.call(manifests, name) ? name as SpaceshipName : null;
}
export interface SpaceshipTextures { body: Texture[][]; effects: Texture[][]; color: number }
/** Each generated pose has its own hull axis; keep that axis on the player's position. */
export function setSpaceshipFrame(sprite: Sprite, texture: Texture) {
  sprite.texture = texture;
  const anchor = texture.defaultAnchor;
  sprite.anchor.set(anchor?.x ?? 0.5, anchor?.y ?? 0.5);
}
export async function loadSpaceship(code: string): Promise<SpaceshipTextures | null> {
  const name = spaceshipName(code);
  if (!name) return null;
  const bundle = manifests[name];
  const sheets = await Promise.all([bundle.body, bundle.effects].map(async config => {
    const sheet = await Assets.load<Texture>(`${import.meta.env.BASE_URL}${config.path}`);
    sheet.source.style.scaleMode = 'nearest';
    return config.frames.map(frame => new Texture({source: sheet.source,
      frame: new Rectangle(frame.x, frame.y, frame.width, frame.height),
      defaultAnchor: {x: frame.centerX / frame.width, y: frame.centerY / frame.height}}));
  }));
  const rows = (frames: Texture[]) => Array.from({length: 4}, (_, i) => frames.slice(i * 4, i * 4 + 4));
  return {body: rows(sheets[0]), effects: rows(sheets[1]), color: parseInt(bundle.color.slice(1), 16)};
}
