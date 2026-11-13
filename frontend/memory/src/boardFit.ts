export type BoardFit = { columns: number; rows: number; size: number }

/** Largest card the game draws, so a few cards on a huge screen do not turn into posters. */
const MAX_CARD = 200
/** Tie-break only: a layout with every row full wins when the cards would be (almost) the same size. */
const FULL_ROWS_BONUS = 1.02

/**
 * Picks how many columns the square cards take so they are as large as the board allows.
 * A wide board gets more columns (20 cards → 7 + 7 + 6), a tall phone board fewer (4 × 5).
 */
export function fitBoard(count: number, width: number, height: number, gap: number): BoardFit {
  let best: BoardFit = { columns: Math.max(1, Math.min(4, count)), rows: Math.max(1, Math.ceil(count / 4)), size: 0 }
  let bestScore = -1
  for (let columns = 1; columns <= count; columns += 1) {
    const rows = Math.ceil(count / columns)
    const size = Math.min(MAX_CARD, Math.floor(Math.min(
      (width - (columns - 1) * gap) / columns,
      (height - (rows - 1) * gap) / rows
    )))
    if (size <= 0) continue
    const score = size * (count % columns === 0 ? FULL_ROWS_BONUS : 1)
    if (score > bestScore) {
      bestScore = score
      best = { columns, rows, size }
    }
  }
  return best
}

/** Sideways shift (px) that centres the cards of a last row that is not full; 0 for every other card. */
export function lastRowShift(index: number, count: number, columns: number, size: number, gap: number): number {
  const inLastRow = count % columns
  if (inLastRow === 0 || index < count - inLastRow) return 0
  return ((columns - inLastRow) * (size + gap)) / 2
}
