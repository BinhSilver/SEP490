export type ConnectionState = 'connecting' | 'online' | 'offline'
export type CardState = 'HIDDEN' | 'TEMP_REVEALED' | 'MATCHED'
/** Trường của thẻ flashcard mà mặt thẻ đang hiển thị. */
export type DeckField = 'KANJI' | 'HIRAGANA' | 'MEANING' | 'HAN_VIET'
export type SessionStatus = 'RUNNING' | 'PAUSED' | 'FINISHED' | 'ABORTED'

export interface MemoryCard {
  cardInstanceId: string
  position: number
  state: CardState
  face?: DeckField
  content?: string
  matchedByUserId?: number
}

export interface MemoryPlayer {
  userId: number
  displayName: string
  avatar?: string
  slot: number
  pairsFound: number
  mistakes: number
  moves: number
  streak: number
  bestStreak: number
  accuracyPercent?: number
  averageDecisionMs?: number
  connected: boolean
  currentTurn: boolean
}

export interface MemoryConfig {
  boardSize: number
  pairCount: number
  objective: 'CLASSIC' | 'TIME_ATTACK'
  pairMode: string
  /** Tên bộ flashcard. */
  level: string
  turnSeconds: number
  totalSeconds?: number
  moveLimit?: number
  keepTurnOnMatch: boolean
  revealDelayMs: number
  questionField: DeckField
  answerField: DeckField
}

export interface MemoryTerm {
  term?: string
  reading?: string
  meaning?: string
  hanViet?: string
  question: string
  answer: string
  mistakes: number
}

export interface MemoryResult {
  success: boolean
  ranked: boolean
  winnerUserId?: number
  draw: boolean
  durationMs: number
  termsEncountered: MemoryTerm[]
  strugglingTerms: MemoryTerm[]
}

export interface MemoryState {
  sessionId: string
  roomId?: string
  playMode: 'SOLO' | 'MULTIPLAYER'
  status: SessionStatus
  config: MemoryConfig
  cards: MemoryCard[]
  players: MemoryPlayer[]
  stateVersion: number
  currentTurnUserId?: number
  resolving: boolean
  pairsMatched: number
  pairsTotal: number
  movesUsed: number
  movesRemaining?: number
  /** Mốc thời gian là epoch ms. */
  turnStartedAt?: number
  turnDeadlineAt?: number
  totalDeadlineAt?: number
  elapsedMs: number
  outcome?: 'CLEARED' | 'TIME_UP' | 'ABANDONED'
  ranked: boolean
  result?: MemoryResult
  deckId: number
  deckTitle: string
  /** Luyện tập: chưa bấm "Bắt đầu" (đang ở màn cài đặt trước ván). */
  awaitingStart: boolean
  serverTime: number
}

/** Một thẻ của bộ (GET /api/games/decks/{setId}/cards); trường thẻ không có là null. */
export interface DeckCardView {
  id: number
  kanji: string | null
  reading: string | null
  meaning: string | null
  hanViet: string | null
}

/** Cài đặt gửi kèm POST /api/games/memory/{sid}/start. */
export interface MemoryStartOptions {
  questionField: DeckField
  answerField: DeckField
  cardIds: number[]
  /** null = server dựng bàn theo đúng số thẻ đã chọn (luyện tập). */
  boardSize: number | null
  objective: 'CLASSIC' | 'TIME_ATTACK'
  totalSeconds: number | null
}

export interface MemoryEnvelope {
  type: string
  payload?: MemoryState | { message?: string }
}

export interface MemoryStoreLike {
  currentUserId: number | null
  serverNow(): number
  applyState(state: MemoryState): void
  connect(): void
  destroy(): void
  flip(cardInstanceId: string): boolean
  on(type: 'change', handler: (state: MemoryState) => void): () => void
  on(type: 'event', handler: (event: MemoryEnvelope) => void): () => void
  on(type: 'connection', handler: (status: ConnectionState) => void): () => void
}

interface LaunchView {
  sessionId: string
  playUrl: string
}

/** Client dùng chung của Game Arena (static/arena/js/arena-client.js). */
interface JlasArenaApi {
  api: {
    get<T>(url: string): Promise<T>
    post<T>(url: string, body?: unknown): Promise<T>
    solo(payload: unknown): Promise<LaunchView>
    leaveSession(sessionId: string): Promise<null>
  }
  live: { serverNow(): number }
  me(): Promise<{ userId: number; name: string; avatarUrl: string | null }>
}

declare global {
  interface Window {
    MemoryStore: new (sessionId: string) => MemoryStoreLike
    JlasArena: JlasArenaApi
  }
}
