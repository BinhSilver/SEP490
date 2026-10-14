/** Raise speed every three resolved cards, reaching twice the starting speed at ten. */
export function cardSpeedMultiplier(completedCards: number): number {
  const cards = Number.isFinite(completedCards) ? Math.max(0, Math.floor(completedCards)) : 0;
  if (cards >= 10) return 2;
  return 1 + Math.floor(cards / 3) * 0.3;
}
