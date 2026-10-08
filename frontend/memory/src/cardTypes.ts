export type CardState = 'HIDDEN' | 'TEMP_REVEALED' | 'MATCHED'
/** Trường của thẻ flashcard mà mặt thẻ đang hiển thị. */
export type DeckField = 'KANJI' | 'HIRAGANA' | 'MEANING' | 'HAN_VIET'

export interface MemoryCard {
  cardInstanceId: string
  position: number
  state: CardState
  face?: DeckField
  content?: string
  matchedByUserId?: number
}
