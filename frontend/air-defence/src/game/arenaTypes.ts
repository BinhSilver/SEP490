// Kiểu dữ liệu của JLAS Game Arena (khớp với com.jlas.game.dto.GameDtos / AirDefenseDtos).

export type DeckField = "KANJI" | "HIRAGANA" | "MEANING" | "HAN_VIET";
export type PlayMode = "SOLO" | "VERSUS";
export type SessionStatus = "RUNNING" | "PAUSED" | "FINISHED" | "ABORTED";
export type GameType = "NEKO_CATCH" | "MEMORY_MATCH" | "AIR_DEFENSE";
export type LiveStatus = "idle" | "connecting" | "online" | "offline";

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

export interface PresencePayload {
  userId: number;
  online: boolean;
}

export interface SessionFinishedPayload<D = unknown> {
  summary: ResultSummary | null;
  detail: D;
}

// ------------------------------------------------------------------ Air Defence

export interface AirWordView {
  id: string;
  prompt: string;
  answer: string;
  kanji: string | null;
  reading: string | null;
  meaning: string | null;
  hanViet: string | null;
}

export interface AirConfigView {
  durationSeconds: number;
  questionField: DeckField;
  answerField: DeckField;
  questionLabel: string;
  answerLabel: string;
  versus: boolean;
  attackMilestones: number[];
}

export interface ShopItemView {
  id: number;
  renderCode: string;
  /** Thư mục gói skin khi renderCode = PACK; weaponTheme: bộ vũ khí máy bay gốc được mượn. */
  packPath?: string | null;
  weaponTheme?: string | null;
  code: string;
  name: string;
  description: string;
  price: number;
  image: string;
  starter: boolean;
  owned: boolean;
  equipped: boolean;
}

export interface ShopSectionView {
  gameType: GameType;
  gameName: string;
  title: string;
  equippedCode: string;
  items: ShopItemView[];
}

export interface ShopView {
  balance: number;
  sections: ShopSectionView[];
}

export interface AirPlayerView {
  userId: number;
  displayName: string;
  avatarUrl: string | null;
  slot: number;
  hp: number;
  maxHp: number;
  score: number;
  combo: number;
  bestCombo: number;
  wave: number;
  verifiedKills: number;
  wrongInputs: number;
  alive: boolean;
  finished: boolean;
  online: boolean;
}

export interface AirAttackView {
  attackId: string;
  fromUserId: number;
  fromName: string;
  milestone: number;
  word: AirWordView;
  bossHp: number;
}

export interface AirStateView {
  sessionId: string;
  mode: PlayMode;
  roomId: string | null;
  status: SessionStatus;
  deckId: number;
  deckTitle: string;
  config: AirConfigView;
  words: AirWordView[];
  meUserId: number;
  players: AirPlayerView[];
  startAt: number;
  endsAt: number | null;
  result: ResultSummary | null;
  /** Luyện tập: đang ở màn cài đặt, chưa bấm "Bắt đầu". */
  awaitingStart: boolean;
  serverTime: number;
}

/** Một thẻ của bộ (màn cài đặt chọn thẻ). */
export interface DeckCardView {
  id: number;
  kanji: string | null;
  reading: string | null;
  meaning: string | null;
  hanViet: string | null;
}

/** POST /api/games/air-defense/{sid}/start — durationSeconds 0 = không giới hạn. */
export interface AirStartOptions {
  questionField: DeckField;
  answerField: DeckField;
  cardIds: number[];
  durationSeconds: number;
}

export interface AirKillResult {
  verified: boolean;
  verifiedKills: number;
}

export interface AirReport {
  hp: number;
  maxHp: number;
  score: number;
  combo: number;
  bestCombo: number;
  wave: number;
  wrongInputs: number;
}

export interface AirAttackResult {
  sent: boolean;
  message: string;
}

// ------------------------------------------------------------------ window.JlasArena

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

export interface ArenaError extends Error {
  status?: number;
  code?: string | null;
}

declare global {
  interface Window {
    JlasArena?: JlasArenaApi;
  }
}
