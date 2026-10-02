export type BonusItemType = 'golden_fish' | 'clock' | 'shield' | 'freeze';
export type AnswerItemType = 'fish_blue' | 'fish_pink' | 'fish_green' | 'paw_cookie';
export type ItemType = AnswerItemType | BonusItemType;

export interface FallingObject {
  id: string;
  x: number;
  y: number;
  speed: number;
  width: number;
  height: number;
  type: ItemType;
  /** Server round this item fell with (bonus items keep it after the round advances). */
  roundId: string;
  /** Answer fish: the server choice id; null for bonus items. */
  choiceId: string | null;
  text: string;
  /** Render the answer text with the Japanese font (answer field KANJI/HIRAGANA). */
  japanese: boolean;
  isBonus?: boolean;
  bonusType?: BonusItemType;
  rotation: number;
  rotSpeed: number;
}

export type CatState = 'idle' | 'run_left' | 'run_right' | 'catch_success' | 'catch_wrong' | 'win' | 'lose';

export interface CatPlayer {
  x: number;
  y: number;
  width: number;
  height: number;
  speed: number;
  state: CatState;
  frameIndex: number;
  basketWidth: number;
  basketHeight: number;
  basketOffsetX: number;
  basketOffsetY: number;
  shieldActive: boolean;
}

export interface SoundConfig {
  bgmVolume: number;
  sfxVolume: number;
  muted: boolean;
}
