import { Assets, Rectangle, Texture } from 'pixi.js';

export const ORNATE_WEAPON_THEMES = {
  'void-blade': { primary: 0xff315e, secondary: 0xff8da6, core: 0xfff0f4, metal: 0xd4b8c8 },
  'halo-fighter': { primary: 0xffc958, secondary: 0xffe9a4, core: 0xfffcf1, metal: 0xf2d68a },
  'aegis-frame': { primary: 0x80d9ff, secondary: 0xcceaff, core: 0xf5fdff, metal: 0xd9e6f3 },
  'split-core': { primary: 0xba7cff, secondary: 0x63f3fa, core: 0xf7f2ff, metal: 0xe0cdfb }
} as const;

export type OrnateWeaponSkin = keyof typeof ORNATE_WEAPON_THEMES;
export type OrnateWeaponTheme = { primary: number; secondary: number; core: number; metal: number };
export interface OrnateWeaponTextures {
  seal: Texture;
  projectile: Texture;
  impact: Texture;
  charge: Texture;
}

export function ornateWeaponSkin(code: string | null | undefined): OrnateWeaponSkin | null {
  const name = code?.replace(/^plane-/, '');
  return name && Object.prototype.hasOwnProperty.call(ORNATE_WEAPON_THEMES, name)
    ? name as OrnateWeaponSkin : null;
}

const textureLoads = new Map<OrnateWeaponSkin, Promise<OrnateWeaponTextures>>();

/** Generated atlases can have different resolutions; never assume a 1024px source. */
export function sliceOrnateWeaponAtlas(sheet: Texture): OrnateWeaponTextures {
  const width = sheet.source.width;
  const height = sheet.source.height;
  if (!Number.isFinite(width) || !Number.isFinite(height) || width < 2 || height < 2) {
    throw new Error('Invalid ornate weapon atlas dimensions');
  }
  const halfWidth = width / 2;
  const halfHeight = height / 2;
  const frame = (column: number, row: number) => new Texture({
    source: sheet.source,
    frame: new Rectangle(column * halfWidth, row * halfHeight, halfWidth, halfHeight),
    defaultAnchor: { x: 0.5, y: 0.5 }
  });
  return { seal: frame(0, 0), projectile: frame(1, 0), impact: frame(0, 1), charge: frame(1, 1) };
}

/** Only the equipped theme is requested. Failed loads may retry on the next mount. */
export function loadOrnateWeaponSprites(skin: OrnateWeaponSkin): Promise<OrnateWeaponTextures> {
  const cached = textureLoads.get(skin);
  if (cached) return cached;
  const pending = Assets.load<Texture>(`${import.meta.env.BASE_URL}assets/space/skins/${skin}/weapon-vfx-v1.png`)
    .then(sheet => {
      sheet.source.style.scaleMode = 'linear';
      return sliceOrnateWeaponAtlas(sheet);
    })
    .catch(error => {
      textureLoads.delete(skin);
      throw error;
    });
  textureLoads.set(skin, pending);
  return pending;
}
