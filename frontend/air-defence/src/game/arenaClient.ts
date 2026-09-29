// Lớp mỏng quanh window.JlasArena (arena-client.js do trang Thymeleaf nạp sẵn): REST + SSE + đồng hồ server.
// Không tự fetch/xác thực ở đây — mọi request đi qua JlasArena.api để dùng chung cookie và làm mới token.

import type {
  AirAttackResult,
  AirKillResult,
  AirReport,
  AirStartOptions,
  AirStateView,
  DeckCardView,
  ArenaEnvelope,
  ArenaError,
  JlasArenaApi,
  LaunchView,
  LiveStatus,
  SoloRequest,
} from "./arenaTypes";

export function getArena(): JlasArenaApi {
  const arena = window.JlasArena;
  if (!arena) {
    throw Object.assign(new Error("Không mở được trò chơi. Hãy tải lại trang."), { status: 0 });
  }
  return arena;
}

export function hasArena(): boolean {
  return typeof window !== "undefined" && !!window.JlasArena;
}

// ---------------------------------------------------------------------------- server clock

let fallbackSkewMs = 0;
let liveClockKnown = false;

/** Ghi nhận giờ server từ một phản hồi REST (dùng khi SSE chưa gửi gói nào). */
export function noteServerTime(serverTime: number | null | undefined, requestStartedAt: number) {
  if (!serverTime) return;
  const midpoint = (requestStartedAt + Date.now()) / 2;
  fallbackSkewMs = serverTime - midpoint;
}

/** Giờ server (epoch ms). Ưu tiên JlasArena.live.serverNow() khi SSE đã đồng bộ đồng hồ. */
export function serverNow(): number {
  const arena = window.JlasArena;
  if (arena && liveClockKnown) return arena.live.serverNow();
  return Date.now() + fallbackSkewMs;
}

// ---------------------------------------------------------------------------- SSE

export function connectLive(): () => void {
  const arena = getArena();
  arena.live.connect();
  // Mọi gói SSE đều mang serverTime → từ gói đầu tiên JlasArena.live đã có độ lệch đồng hồ.
  return arena.live.on("*", () => {
    liveClockKnown = true;
  });
}

/** Đăng ký một loại sự kiện, chỉ nhận gói của đúng trận này. */
export function onSessionEvent<T>(sessionId: string, type: string, handler: (payload: T, envelope: ArenaEnvelope<T>) => void): () => void {
  return getArena().live.on<T>(type, (payload, envelope) => {
    if (!envelope || envelope.sessionId !== sessionId) return;
    handler(payload, envelope);
  });
}

export function onReconnect(handler: () => void): () => void {
  return getArena().live.on<{ reconnected?: boolean }>("CONNECTED", (payload) => {
    if (payload && payload.reconnected) handler();
  });
}

export function onLiveStatus(handler: (status: LiveStatus) => void): () => void {
  return getArena().live.onStatus(handler);
}

// ---------------------------------------------------------------------------- REST

const base = (sessionId: string) => `/api/games/air-defense/${encodeURIComponent(sessionId)}`;

export async function fetchAirState(sessionId: string): Promise<AirStateView> {
  const started = Date.now();
  const state = await getArena().api.get<AirStateView>(base(sessionId));
  noteServerTime(state?.serverTime, started);
  return state;
}

export async function postStart(sessionId: string, options: AirStartOptions): Promise<AirStateView> {
  const started = Date.now();
  const state = await getArena().api.post<AirStateView>(`${base(sessionId)}/start`, options);
  noteServerTime(state?.serverTime, started);
  return state;
}

export function fetchDeckCards(setId: number): Promise<DeckCardView[]> {
  return getArena().api.get<DeckCardView[]>(`/api/games/decks/${encodeURIComponent(String(setId))}/cards`);
}

export function postKill(sessionId: string, wordId: string, rawInput: string): Promise<AirKillResult> {
  return getArena().api.post<AirKillResult>(`${base(sessionId)}/kill`, { wordId, rawInput });
}

export async function postReport(sessionId: string, report: AirReport): Promise<AirStateView> {
  const started = Date.now();
  const state = await getArena().api.post<AirStateView>(`${base(sessionId)}/report`, report);
  noteServerTime(state?.serverTime, started);
  return state;
}

export function postAttack(sessionId: string, milestone: number): Promise<AirAttackResult> {
  return getArena().api.post<AirAttackResult>(`${base(sessionId)}/attack`, { milestone });
}

export async function postFinish(sessionId: string, defeated: boolean, report: AirReport): Promise<AirStateView> {
  const started = Date.now();
  const state = await getArena().api.post<AirStateView>(`${base(sessionId)}/finish?defeated=${defeated ? "true" : "false"}`, report);
  noteServerTime(state?.serverTime, started);
  return state;
}

export function leaveSession(sessionId: string): Promise<null> {
  return getArena().api.leaveSession(sessionId);
}

export function startSolo(payload: SoloRequest): Promise<LaunchView> {
  return getArena().api.solo(payload);
}

export function errorMessage(error: unknown, fallback = "Có lỗi xảy ra, vui lòng thử lại."): string {
  const err = error as ArenaError | null;
  return err && typeof err.message === "string" && err.message ? err.message : fallback;
}

export function errorStatus(error: unknown): number {
  const err = error as ArenaError | null;
  return err && typeof err.status === "number" ? err.status : 0;
}
