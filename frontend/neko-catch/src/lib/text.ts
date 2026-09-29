import type { DeckField, NekoDifficulty } from '../arena/types';

/** Japanese content (Kanji / reading) uses a Japanese font; Vietnamese content a Latin UI font with full diacritics. */
export const JP_FONT_STACK = "'M PLUS Rounded 1c', 'Noto Sans JP', 'Hiragino Maru Gothic ProN', 'Meiryo', sans-serif";
export const VI_FONT_STACK = "'Nunito', 'Segoe UI', 'Helvetica Neue', Arial, sans-serif";
export const DISPLAY_FONT_STACK = "'Fredoka', 'Nunito', sans-serif";

export function isJapaneseField(field: DeckField | null | undefined): boolean {
  return field === 'KANJI' || field === 'HIRAGANA';
}

export function fieldFont(field: DeckField | null | undefined): string {
  return isJapaneseField(field) ? JP_FONT_STACK : VI_FONT_STACK;
}

export function difficultyLabel(difficulty: NekoDifficulty | string | null | undefined): string {
  switch (difficulty) {
    case 'EASY':
      return 'Dễ';
    case 'HARD':
      return 'Khó';
    default:
      return 'Vừa';
  }
}

export function initials(name: string | null | undefined): string {
  const words = String(name || '').trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return '?';
  const first = words[0][0] ?? '';
  const last = words.length > 1 ? words[words.length - 1][0] ?? '' : '';
  return (first + last).toUpperCase();
}
