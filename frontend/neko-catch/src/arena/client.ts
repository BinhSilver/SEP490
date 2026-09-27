import type {
  ArenaError,
  DeckCardView,
  DeckField,
  JlasArenaApi,
  LaunchView,
  NekoActionResult,
  NekoStartOptions,
  NekoStateView,
  ShopView,
} from './types';

/** The shared browser client (fetch + auth refresh + SSE). The host page loads it before this bundle. */
export function arena(): JlasArenaApi {
  const client = window.JlasArena;
  if (!client) {
    throw new Error('Không mở được trò chơi. Hãy tải lại trang.');
  }
  return client;
}

export function hasArena(): boolean {
  return typeof window !== 'undefined' && !!window.JlasArena;
}

const base = (sessionId: string) => `/api/games/neko/${encodeURIComponent(sessionId)}`;

/** REST endpoints of NekoCatchController. Every call resolves with the unwrapped `data`. */
export const nekoApi = {
  state: (sessionId: string) => arena().api.get<NekoStateView>(base(sessionId)),
  catchChoice: (sessionId: string, roundId: string, choiceId: string) =>
    arena().api.post<NekoActionResult>(`${base(sessionId)}/catch`, { roundId, choiceId }),
  miss: (sessionId: string, roundId: string) =>
    arena().api.post<NekoActionResult>(`${base(sessionId)}/miss`, { roundId }),
  bonus: (sessionId: string, roundId: string) =>
    arena().api.post<NekoActionResult>(`${base(sessionId)}/bonus`, { roundId }),
  timeUp: (sessionId: string) => arena().api.post<NekoStateView>(`${base(sessionId)}/time-up`),
  /** Luyện tập: người chơi bấm "Bắt đầu", server đặt startAt = bây giờ + 3 giây. */
  start: (sessionId: string, options: NekoStartOptions) =>
    arena().api.post<NekoStateView>(`${base(sessionId)}/start`, options),
  pause: (sessionId: string) => arena().api.post<NekoStateView>(`${base(sessionId)}/pause`),
  resume: (sessionId: string) => arena().api.post<NekoStateView>(`${base(sessionId)}/resume`),
  leave: (sessionId: string) => arena().api.leaveSession(sessionId),
  /** Thẻ của bộ với 4 trường, cho phần chọn cặp hỏi – đáp và chọn thẻ trước trận luyện tập. */
  deckCards: (setId: number) => arena().api.get<DeckCardView[]>(`/api/games/decks/${setId}/cards`),
  /** Trang phục mèo đã mua / đang dùng (mục Bắt cá trong Cửa hàng). */
  shop: () => arena().api.get<ShopView>('/api/games/shop'),
  equip: (code: string) => arena().api.post<ShopView>(`/api/games/shop/items/${encodeURIComponent(code)}/equip`),
  /** Starts a fresh SOLO match with the same deck, fields and options. */
  soloAgain: (setId: number, questionField: DeckField, answerField: DeckField, difficulty: string, durationSeconds: number) =>
    arena().api.solo({
      gameType: 'NEKO_CATCH',
      settings: { setId, questionField, answerField, options: { difficulty, durationSeconds } },
    }) as Promise<LaunchView>,
};

export function errorMessage(error: unknown, fallback = 'Có lỗi xảy ra, vui lòng thử lại.'): string {
  if (error && typeof error === 'object' && 'message' in error) {
    const message = String((error as ArenaError).message || '').trim();
    if (message) return message;
  }
  return fallback;
}

export function errorStatus(error: unknown): number {
  if (error && typeof error === 'object' && 'status' in error) {
    return Number((error as ArenaError).status) || 0;
  }
  return 0;
}

/**
 * Server clock. JlasArena.live.serverNow() is corrected by the skew of every SSE envelope; until the first
 * envelope arrives it equals Date.now(), so REST replies (which carry serverTime) seed the estimate meanwhile.
 */
class ServerClock {
  private restSkew = 0;
  private hasRestSample = false;
  private liveSeen = false;

  observe(serverTime: number | null | undefined) {
    if (typeof serverTime !== 'number' || !Number.isFinite(serverTime) || serverTime <= 0) return;
    this.restSkew = serverTime - Date.now();
    this.hasRestSample = true;
  }

  markLive() {
    this.liveSeen = true;
  }

  now(): number {
    if (hasArena() && (this.liveSeen || !this.hasRestSample)) {
      return arena().live.serverNow();
    }
    return Date.now() + this.restSkew;
  }
}

export const serverClock = new ServerClock();
