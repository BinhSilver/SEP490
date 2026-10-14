/**
 * Types shared with the Spring Boot arena backend (com.jlas.game.dto.*) and the browser client
 * window.JlasArena defined by /arena/js/arena-client.js (loaded by the Thymeleaf host page).
 */

export type DeckField = 'KANJI' | 'HIRAGANA' | 'MEANING' | 'HAN_VIET';
export type PlayMode = 'SOLO' | 'VERSUS';
export type SessionStatus = 'RUNNING' | 'PAUSED' | 'FINISHED' | 'ABORTED';
export type GameType = 'NEKO_CATCH' | 'MEMORY_MATCH' | 'AIR_DEFENSE';
export type LiveStatus = 'idle' | 'connecting' | 'online' | 'offline';

export interface ArenaEnvelope<T = unknown> {
  type: string;
  roomId: string | null;
  sessionId: string | null;
  payload: T;
  serverTime: number;
}

export interface ResultLine {
  userId: number;
  displayName: string;
  rank: number;
  score: number;
  detail: string;
  winner: boolean;
}

export interface ResultSummary {
  sessionId: string;
  gameType: GameType;
  mode: PlayMode;
  winnerUserId: number | null;
  draw: boolean;
  lines: ResultLine[];
  finishedAt: number;
}

export interface GameOptions {
  difficulty?: string | null;
  durationSeconds?: number | null;
  boardSize?: number | null;
  turnSeconds?: number | null;
  objective?: string | null;
  totalSeconds?: number | null;
  keepTurnOnMatch?: boolean | null;
}

export interface SoloRequest {
  gameType: GameType;
  settings: { setId: number; questionField: DeckField; answerField: DeckField; options: GameOptions };
}

export interface LaunchView {
  sessionId: string;
  gameType: GameType;
  playUrl: string;
}

export interface SessionInfoView {
  sessionId: string;
  gameType: GameType;
  mode: PlayMode;
  roomId: string | null;
  roomCode: string | null;
  status: SessionStatus;
  deckId: number;
  deckTitle: string;
  questionField: DeckField;
  answerField: DeckField;
  players: { userId: number; displayName: string; avatarUrl: string | null; slot: number; online: boolean }[];
  meUserId: number;
  playUrl: string;
  backUrl: string;
  serverTime: number;
}

// ─── Neko Catch ───────────────────────────────────────────

export type NekoBonus = 'GOLDEN_FISH' | 'CLOCK' | 'SHIELD' | 'FREEZE';
export type NekoDifficulty = 'EASY' | 'NORMAL' | 'HARD';
export type NekoFinishReason = 'LIVES' | 'TIME' | 'FORFEIT';

export interface NekoChoiceView {
  id: string;
  text: string;
}

export interface NekoRoundView {
  id: string;
  index: number;
  prompt: string;
  choices: NekoChoiceView[];
  bonus: NekoBonus | null;
}

export interface NekoConfigView {
  difficulty: NekoDifficulty;
  lives: number;
  choices: number;
  /** Số cá tối đa mỗi lượt bộ thẻ cho phép (số đáp án khác nhau, tối đa 7). */
  maxChoices: number;
  durationSeconds: number;
  questionField: DeckField;
  answerField: DeckField;
  questionLabel: string;
  answerLabel: string;
  pausable: boolean;
}

/** Cài đặt chọn ở màn chuẩn bị của trận luyện tập, gửi kèm khi bấm "Bắt đầu". */
export interface NekoStartOptions {
  questionField: DeckField;
  answerField: DeckField;
  /** Thẻ muốn chơi; mọi thẻ đã chọn phải có đủ cả trường hỏi lẫn trường đáp. */
  cardIds: number[];
  durationSeconds: number;
  lives: number;
  choices: number;
}

/** Một món trong Cửa hàng (GET /api/games/shop). */
export interface ShopItemView {
  id: number;
  renderCode: string;
  code: string;
  name: string;
  image: string;
  price: number;
  owned: boolean;
  equipped: boolean;
}

export interface ShopView {
  balance: number;
  sections: { gameType: string; items: ShopItemView[] }[];
}

/** Một thẻ của bộ (GET /api/games/decks/{setId}/cards); trường thẻ không có là null. */
export interface DeckCardView {
  id: number;
  kanji: string | null;
  reading: string | null;
  meaning: string | null;
  hanViet: string | null;
}

export interface NekoPlayerView {
  userId: number;
  displayName: string;
  avatarUrl: string | null;
  slot: number;
  score: number;
  combo: number;
  maxCombo: number;
  correct: number;
  wrong: number;
  lives: number;
  maxLives: number;
  shield: boolean;
  roundIndex: number;
  deadlineAt: number;
  finished: boolean;
  finishReason: NekoFinishReason | null;
  online: boolean;
}

export interface NekoMistakeView {
  prompt: string;
  correctText: string;
  chosenText: string | null;
}

export interface NekoStateView {
  sessionId: string;
  mode: PlayMode;
  roomId: string | null;
  status: SessionStatus;
  deckId: number;
  deckTitle: string;
  config: NekoConfigView;
  rounds: NekoRoundView[];
  meUserId: number;
  players: NekoPlayerView[];
  startAt: number;
  /** Luyện tập: chưa bấm "Bắt đầu" (startAt và hạn giờ chỉ là tạm). */
  awaitingStart: boolean;
  pausedAt: number | null;
  myMistakes: NekoMistakeView[];
  result: ResultSummary | null;
  serverTime: number;
}

export interface NekoActionResult {
  accepted: boolean;
  correct: boolean;
  correctChoiceId: string | null;
  correctText: string | null;
  shieldBlocked: boolean;
  bonusApplied: NekoBonus | null;
  advanced: boolean;
  me: NekoPlayerView;
  finished: boolean;
  serverTime: number;
}

export interface PresencePayload {
  userId: number;
  online: boolean;
}

export interface SessionFinishedPayload {
  summary: ResultSummary | null;
  detail: NekoPlayerView[] | null;
}

// ─── window.JlasArena ─────────────────────────────────────

/** Error thrown by JlasArena.api: Vietnamese user-facing message + HTTP status + backend code. */
export interface ArenaError extends Error {
  status?: number;
  code?: string | null;
}

export interface JlasArenaApi {
  api: {
    get<T>(url: string): Promise<T>;
    post<T>(url: string, body?: unknown): Promise<T>;
    put<T>(url: string, body?: unknown): Promise<T>;
    session(sessionId: string): Promise<SessionInfoView>;
    leaveSession(sessionId: string): Promise<null>;
    solo(payload: SoloRequest): Promise<LaunchView>;
  };
  live: {
    connect(): unknown;
    on<T>(type: string, handler: (payload: T, envelope: ArenaEnvelope<T>) => void): () => void;
    onStatus(handler: (status: LiveStatus) => void): () => void;
    serverNow(): number;
    status: LiveStatus;
  };
  me(): Promise<{ userId: number; name: string; avatarUrl: string | null }>;
  esc(value: unknown): string;
  fieldLabel(field: DeckField): string;
  phoneMedia: MediaQueryList;
  canPlayGame(type: GameType): boolean;
}

declare global {
  interface Window {
    JlasArena?: JlasArenaApi;
  }
}
