// ============================================================================
// NỐI TRẬN AIR DEFENCE VỚI JLAS ARENA
//  - Tải AirStateView rồi vào thẳng trận (intro kết thúc đúng startAt).
//  - SSE: AIR_PROGRESS / SESSION_PRESENCE (HUD đối thủ), AIR_ATTACK (mini-boss), SESSION_FINISHED (kết quả).
//  - /report có nhịp: tối đa 400 ms/lần khi có thay đổi, gửi ngay khi HP đổi.
//  - /finish?defeated=true khi HP về 0; chơi đơn bấm kết thúc → /finish?defeated=false.
//  - Hết giờ đối kháng / chờ kết quả: gửi báo cáo cuối và hỏi lại trạng thái cho tới khi có kết quả.
// ============================================================================

import { buildReport, matchHooks, useAirDefenseStore } from "./useAirDefenseStore";
import {
  connectLive,
  errorMessage,
  errorStatus,
  fetchAirState,
  hasArena,
  postStart,
  leaveSession,
  onLiveStatus,
  onReconnect,
  onSessionEvent,
  postFinish,
  postReport,
  startSolo,
} from "./arenaClient";
import type {
  AirAttackView,
  AirPlayerView,
  AirReport,
  AirStartOptions,
  AirStateView,
  PlayMode,
  PresencePayload,
  SessionFinishedPayload,
} from "./arenaTypes";

export interface HostConfig {
  sessionId: string;
  playMode: PlayMode | null;
  backUrl: string;
  meId: number | null;
}

const REPORT_INTERVAL_MS = 400;
const REPORT_BACKOFF_MS = 1500;
const RESULT_POLL_MS = 3000;

let host: HostConfig | null = null;

// --------------------------------------------------------------------- report throttle

let lastSent: AirReport | null = null;
let lastSentAt = 0;
let backoffUntil = 0;
let inFlight = false;
let pending = false;
let pendingImmediate = false;
let reportTimer: ReturnType<typeof setTimeout> | null = null;

function sameReport(a: AirReport, b: AirReport): boolean {
  return (
    a.hp === b.hp &&
    a.maxHp === b.maxHp &&
    a.score === b.score &&
    a.combo === b.combo &&
    a.bestCombo === b.bestCombo &&
    a.wave === b.wave &&
    a.wrongInputs === b.wrongInputs
  );
}

function clearReportTimer() {
  if (reportTimer !== null) {
    clearTimeout(reportTimer);
    reportTimer = null;
  }
}

function scheduleReport(delayMs: number) {
  if (reportTimer !== null) return;
  reportTimer = setTimeout(() => {
    reportTimer = null;
    void sendReport();
  }, Math.max(0, delayMs));
}

function requestReport(immediate: boolean) {
  if (inFlight) {
    pending = true;
    pendingImmediate = pendingImmediate || immediate;
    return;
  }
  const now = Date.now();
  if (now < backoffUntil) {
    scheduleReport(backoffUntil - now);
    return;
  }
  const elapsed = now - lastSentAt;
  if (immediate || elapsed >= REPORT_INTERVAL_MS) {
    clearReportTimer();
    void sendReport();
    return;
  }
  scheduleReport(REPORT_INTERVAL_MS - elapsed);
}

async function sendReport(force = false) {
  const state = useAirDefenseStore.getState();
  if (!host || !state.match) return;
  if (!force && state.outcome !== "playing") return;
  const report = buildReport(state);
  if (!force && lastSent && sameReport(lastSent, report)) return;

  inFlight = true;
  lastSent = report;
  lastSentAt = Date.now();
  try {
    const view = await postReport(host.sessionId, report);
    if (view) useAirDefenseStore.getState().syncFromServer(view);
  } catch {
    // Mạng chập chờn / bị giới hạn tần suất: gửi lại sau một nhịp nghỉ.
    lastSent = null;
    backoffUntil = Date.now() + REPORT_BACKOFF_MS;
  } finally {
    inFlight = false;
    if (pending) {
      const immediate = pendingImmediate;
      pending = false;
      pendingImmediate = false;
      requestReport(immediate);
    } else if (lastSent === null && useAirDefenseStore.getState().outcome === "playing") {
      requestReport(false);
    }
  }
}

function onStoreChange() {
  const state = useAirDefenseStore.getState();
  if (!state.match || state.outcome !== "playing") return;
  const report = buildReport(state);
  if (lastSent && sameReport(lastSent, report)) return;
  const hpChanged = !lastSent || lastSent.hp !== report.hp || lastSent.maxHp !== report.maxHp;
  requestReport(hpChanged);
}

// --------------------------------------------------------------------- finish & result

let resultPoll: ReturnType<typeof setInterval> | null = null;

function stopResultPolling() {
  if (resultPoll !== null) {
    clearInterval(resultPoll);
    resultPoll = null;
  }
}

/** Chờ SESSION_FINISHED; nếu gói SSE bị lỡ thì tự hỏi lại trạng thái trận. */
function startResultPolling() {
  if (resultPoll !== null || !host) return;
  const sessionId = host.sessionId;
  resultPoll = setInterval(() => {
    const state = useAirDefenseStore.getState();
    if (state.outcome === "over") {
      stopResultPolling();
      return;
    }
    if (state.outcome === "playing") return;
    fetchAirState(sessionId)
      .then((view) => useAirDefenseStore.getState().syncFromServer(view))
      .catch(() => undefined);
  }, RESULT_POLL_MS);
}

function delay(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function finishWithRetry(defeated: boolean): Promise<AirStateView> {
  if (!host) throw new Error("Chưa kết nối trận đấu");
  const report = buildReport(useAirDefenseStore.getState());
  if (defeated) report.hp = 0;
  let lastError: unknown = null;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      return await postFinish(host.sessionId, defeated, report);
    } catch (error) {
      lastError = error;
      const status = errorStatus(error);
      // 4xx (trừ 429) là lỗi nghiệp vụ, thử lại cũng vậy.
      if (status >= 400 && status < 500 && status !== 429) break;
      await delay(800 * (attempt + 1));
    }
  }
  throw lastError;
}

function handleDefeated() {
  clearReportTimer();
  finishWithRetry(true)
    .then((view) => useAirDefenseStore.getState().syncFromServer(view))
    .catch((error) => {
      useAirDefenseStore.getState().setNotice(errorMessage(error, "Chưa lưu được kết quả trận. Kiểm tra mạng rồi thử lại."));
    })
    .finally(() => {
      if (useAirDefenseStore.getState().outcome !== "over") startResultPolling();
    });
}

function handleTimeUp() {
  clearReportTimer();
  // Báo cáo cuối cùng để server chốt HP/điểm, sau đó chờ SESSION_FINISHED (server chốt ≈2 giây sau endsAt).
  void sendReport(true).finally(() => startResultPolling());
}

/** Chơi đơn: dừng trận, xem tổng kết (server lưu kết quả "SURVIVED"). */
export async function finishSoloMatch(): Promise<void> {
  const store = useAirDefenseStore.getState();
  if (!host || !store.match || store.match.playMode !== "SOLO" || store.outcome !== "playing") return;
  clearReportTimer();
  store.setOutcome("stopping");
  try {
    const view = await finishWithRetry(false);
    useAirDefenseStore.getState().syncFromServer(view);
    if (useAirDefenseStore.getState().outcome !== "over") startResultPolling();
  } catch (error) {
    const current = useAirDefenseStore.getState();
    if (current.outcome === "stopping") current.setOutcome("playing");
    current.setNotice(errorMessage(error, "Không kết thúc được trận. Vui lòng thử lại."));
  }
}

/** "Rời trận": trong đối kháng rời trận = thua. */
export async function leaveMatch(): Promise<void> {
  if (!host) return;
  const target = host.backUrl;
  try {
    await leaveSession(host.sessionId);
  } catch {
    // Trận có thể đã kết thúc; vẫn rời trang.
  }
  window.location.href = target;
}

/** Luyện tập: gửi cài đặt ở màn chuẩn bị; server dựng lại dãy từ, intro đếm ngược tới startAt. */
export async function startSoloMatch(options: AirStartOptions): Promise<void> {
  if (!host) throw new Error("Chưa kết nối trận đấu");
  const view = await postStart(host.sessionId, options);
  const store = useAirDefenseStore.getState();
  if (view.awaitingStart) {
    store.showSetup(view);
    return;
  }
  lastSent = null;
  store.initMatch(view);
}

/** Màn cài đặt "Quay về": bỏ ván chưa chơi rồi về trang trước. */
export const leaveSetup = leaveMatch;

/** Chơi đơn "Chơi lại": tạo ván mới cùng bộ thẻ / trường hỏi-đáp, về lại màn cài đặt. */
export async function replaySolo(): Promise<void> {
  const match = useAirDefenseStore.getState().match;
  if (!match) return;
  const launch = await startSolo({
    gameType: "AIR_DEFENSE",
    settings: {
      setId: match.deckId,
      questionField: match.questionField,
      answerField: match.answerField,
      options: { durationSeconds: 0 },
    },
  });
  window.location.href = launch.playUrl;
}

export function backUrl(): string {
  return host?.backUrl || "/games";
}

// --------------------------------------------------------------------- boot

async function loadInitial(sessionId: string) {
  try {
    const state = await fetchAirState(sessionId);
    const store = useAirDefenseStore.getState();
    if (state.awaitingStart && state.mode === "SOLO" && state.status === "RUNNING") {
      store.showSetup(state);
      return;
    }
    store.initMatch(state);
    const after = useAirDefenseStore.getState();
    if (after.outcome === "defeated" || after.outcome === "timeUp") {
      if (after.outcome === "timeUp") void sendReport(true);
      startResultPolling();
    }
  } catch (error) {
    useAirDefenseStore.getState().setBootError(errorMessage(error, "Không tải được trận đấu. Vui lòng thử lại."));
  }
}

async function refresh(sessionId: string) {
  try {
    const state = await fetchAirState(sessionId);
    const store = useAirDefenseStore.getState();
    if (store.bootStatus === "setup") {
      if (!state.awaitingStart) store.initMatch(state);
      return;
    }
    if (store.bootStatus !== "ready") {
      store.initMatch(state);
    } else {
      store.syncFromServer(state);
    }
  } catch {
    // Lần kết nối lại sau sẽ thử tiếp.
  }
}

export function startMatchController(config: HostConfig): () => void {
  host = config;
  const store = useAirDefenseStore;

  if (!hasArena()) {
    store.getState().setBootError("Không mở được trò chơi. Hãy tải lại trang.");
    return () => undefined;
  }

  const sessionId = config.sessionId;
  const offs: Array<() => void> = [];

  matchHooks.onDefeated = handleDefeated;
  matchHooks.onTimeUp = handleTimeUp;

  try {
    offs.push(connectLive());
    offs.push(onLiveStatus((status) => store.getState().setLiveStatus(status)));
    offs.push(onSessionEvent<AirPlayerView>(sessionId, "AIR_PROGRESS", (payload) => {
      if (payload && typeof payload.userId === "number") store.getState().applyPlayer(payload);
    }));
    offs.push(onSessionEvent<AirAttackView>(sessionId, "AIR_ATTACK", (payload) => {
      if (payload) store.getState().receiveAttack(payload);
    }));
    offs.push(onSessionEvent<PresencePayload>(sessionId, "SESSION_PRESENCE", (payload) => {
      if (payload && typeof payload.userId === "number") store.getState().applyPresence(payload.userId, !!payload.online);
    }));
    offs.push(onSessionEvent<SessionFinishedPayload<AirPlayerView[] | null>>(sessionId, "SESSION_FINISHED", (payload) => {
      if (payload && Array.isArray(payload.detail)) store.getState().applyPlayers(payload.detail);
      store.getState().showResult(payload ? payload.summary : null);
    }));
    offs.push(onReconnect(() => void refresh(sessionId)));
  } catch (error) {
    store.getState().setBootError(errorMessage(error, "Mất kết nối với trận đấu. Kiểm tra mạng rồi tải lại trang."));
  }

  offs.push(store.subscribe(onStoreChange));

  void loadInitial(sessionId);

  return () => {
    offs.forEach((off) => {
      try {
        off();
      } catch {
        // bỏ qua
      }
    });
    clearReportTimer();
    stopResultPolling();
    matchHooks.onDefeated = undefined;
    matchHooks.onTimeUp = undefined;
  };
}
