export const SOLO_DURATIONS = [60, 90, 120, 180, 300, 600, 900, 1200, 1800, 3600];
export const SECONDS_PER_TURN = 7;
export function recommendDuration(cardCount: number): number {
  const target = cardCount * 2 * SECONDS_PER_TURN;
  return SOLO_DURATIONS.reduce((best, seconds) => Math.abs(seconds - target) < Math.abs(best - target) ? seconds : best);
}
export const durationLabel = (seconds: number) => seconds < 180 ? `${seconds}s` : `${seconds / 60} phút`;
