// ============================================================================
// AIR DEFENCE — STORE MÔ PHỎNG TRẬN ĐẤU (JLAS Game Arena)
// Mô phỏng đợt địch, loot, augment, hyper beam và điểm số chạy ở trình duyệt; server giữ dãy từ chung,
// kiểm lại từng lần bắn trúng (/kill), nhận HP/điểm (/report) và chuyển tiếp đòn combo (/attack).
// Phần nối mạng có nhịp (report, finish, SSE) nằm ở matchController.ts.
// ============================================================================

import { create } from "zustand";
import type { AugmentCard, AudioSettings, FloatingText, GameMode, LootItem, LootItemType, Screen, TargetWord, WeakWord } from "./types";
import { GAME_CONFIG } from "./gameConfig";
import { soundManager } from "./soundEffects";
import { isPartialAnswer, matchesAnswer } from "./answerMatcher";
import type { ComboMilestoneData } from "./ComboSplashOverlay";
import type {
  AirAttackView,
  AirPlayerView,
  AirReport,
  AirStateView,
  AirWordView,
  DeckField,
  LiveStatus,
  PlayMode,
  ResultSummary,
} from "./arenaTypes";
import { postAttack, postKill, serverNow } from "./arenaClient";

/** Arena dùng tàu mặc định, không có hangar/talent: 100 HP, không nội tại. */
export const DEFAULT_SHIP = GAME_CONFIG.SHIPS[0];

/** Hình máy bay đang dùng (chọn ở Cửa hàng, trang host truyền qua data-skin). */
let planeSprite = DEFAULT_SHIP.spritePath;

export function setPlaneSkin(code: string | null | undefined) {
  planeSprite = (code && GAME_CONFIG.PLANE_SPRITES[code]) || DEFAULT_SHIP.spritePath;
}

export function playerPlaneSprite(): string {
  return planeSprite;
}

export const AUGMENTS_POOL: AugmentCard[] = [
  {
    id: "DUAL_CANNON",
    title: "PHÁO ĐÔI PLASMA",
    category: "OFFENSE",
    description: "Tăng +50% điểm số nhận được mỗi khi bắn nổ quái vật.",
    icon: "fa-angles-up",
    tone: "cyan"
  },
  {
    id: "CRYO_PAYLOAD",
    title: "ĐẠN ĐÓNG BĂNG",
    category: "CONTROL",
    description: "Giảm 35% tốc độ rơi của toàn bộ quái vật không gian.",
    icon: "fa-snowflake",
    tone: "violet"
  },
  {
    id: "GOLD_MAGNET",
    title: "NAM CHÂM XU",
    category: "UTILITY",
    description: "Nhận thêm 50% xu mỗi lần bắn trúng.",
    icon: "fa-magnet",
    tone: "amber"
  },
  {
    id: "SHIELD_BARRIER",
    title: "LÁ CHẮN LƯỢNG TỬ",
    category: "DEFENSE",
    description: "Thêm ngay 3 lớp khiên chặn địch.",
    icon: "fa-shield-halved",
    tone: "cyan"
  },
  {
    id: "REPAIR_NANO",
    title: "HỒI MÁU",
    category: "SURVIVAL",
    description: "Hồi ngay 30% máu.",
    icon: "fa-kit-medical",
    tone: "amber"
  },
  {
    id: "CHRONO_FREEZE",
    title: "NGƯNG ĐỌNG THỜI GIAN",
    category: "TACTICAL",
    description: "Cứ mỗi 5 combo, địch đứng yên 4 giây.",
    icon: "fa-hourglass-half",
    tone: "violet"
  }
];

// ----------------------------------------------------------------------------
// Thông tin trận (từ AirStateView)
// ----------------------------------------------------------------------------

export interface MatchInfo {
  sessionId: string;
  playMode: PlayMode;
  versus: boolean;
  meId: number;
  roomId: string | null;
  deckId: number;
  deckTitle: string;
  questionField: DeckField;
  answerField: DeckField;
  questionLabel: string;
  answerLabel: string;
  attackMilestones: number[];
  startAt: number;
  endsAt: number | null;
  /** Dãy từ chung của trận (cùng thứ tự cho cả hai người, id w0..wN). */
  words: AirWordView[];
}

/**
 * playing  — đang mô phỏng (trừ khi tạm dừng / intro / chọn lõi)
 * stopping — người chơi đơn bấm kết thúc, đang chờ /finish
 * defeated — hành tinh về 0 HP, chờ kết quả
 * timeUp   — đối kháng hết giờ, chờ server chốt kết quả
 * over     — đã có kết quả (hoặc trận bị huỷ)
 */
export type MatchOutcome = "playing" | "stopping" | "defeated" | "timeUp" | "over";

/** "setup": luyện tập đang ở màn cài đặt trước trận (xem AirSetup). */
export type BootStatus = "loading" | "setup" | "ready" | "error";

export interface ArenaBanner {
  id: number;
  kind: "incoming" | "sent";
  title: string;
  detail?: string;
}

/** Callback do matchController đăng ký để store báo các mốc cần gọi server. */
export const matchHooks: { onDefeated?: () => void; onTimeUp?: () => void } = {};

export function isJapaneseField(field: DeckField | null | undefined): boolean {
  return field === "KANJI" || field === "HIRAGANA";
}

export function isBossWave(wave: number): boolean {
  return wave % GAME_CONFIG.ENEMIES.bossEveryNWaves === 0;
}

export function bossHpForWave(wave: number): number {
  return GAME_CONFIG.ENEMIES.bossHp + Math.max(0, Math.floor((wave - 1) / GAME_CONFIG.ENEMIES.bossEveryNWaves));
}

function minionCount(wave: number): number {
  const rawCount = Math.min(
    GAME_CONFIG.ENEMIES.maxEnemyCount,
    GAME_CONFIG.ENEMIES.baseEnemyCount + (wave - 1) * GAME_CONFIG.ENEMIES.enemiesPerWaveIncrement
  );
  return isBossWave(wave) ? Math.max(3, Math.round(rawCount * GAME_CONFIG.ENEMIES.bossMinionReduction)) : rawCount;
}

function wordsUsedByWave(wave: number): number {
  return minionCount(wave) + (isBossWave(wave) ? 1 : 0);
}

/**
 * Vị trí con trỏ trong dãy từ khi bắt đầu đợt `wave`. Mỗi đợt tiêu thụ đúng số từ bằng số địch (+1 cho boss),
 * nên hai người chơi ở cùng một đợt luôn gặp cùng những từ, theo đúng thứ tự — kể cả khi tải lại trang.
 */
export function waveCursorStart(wave: number): number {
  let cursor = 0;
  for (let w = 1; w < wave; w++) cursor += wordsUsedByWave(w);
  return cursor;
}

function wordAt(words: AirWordView[], index: number): AirWordView {
  const n = words.length;
  return words[((index % n) + n) % n];
}

function wordFields(word: AirWordView) {
  return {
    wordId: word.id,
    prompt: word.prompt,
    answer: word.answer,
    kanji: word.kanji,
    reading: word.reading,
    meaning: word.meaning,
    hanViet: word.hanViet
  };
}

let targetSerial = 0;
function nextTargetId(prefix: string): string {
  targetSerial += 1;
  return `${prefix}-${Date.now().toString(36)}-${targetSerial}`;
}

function baseSpeedForWave(wave: number): number {
  return GAME_CONFIG.ENEMIES.baseSpeed + wave * GAME_CONFIG.ENEMIES.speedWaveMultiplier;
}

export function generateWave(wave: number, words: AirWordView[]): TargetWord[] {
  if (words.length === 0) return [];
  const bossWave = isBossWave(wave);
  const count = minionCount(wave);
  let cursor = waveCursorStart(wave);
  const waveTargets: TargetWord[] = [];

  if (bossWave) {
    const bossWord = wordAt(words, cursor);
    cursor += 1;
    const scaledBossHp = bossHpForWave(wave);
    waveTargets.push({
      id: nextTargetId(`boss-w${wave}`),
      ...wordFields(bossWord),
      posX: 50,
      posY: -22,
      speed: baseSpeedForWave(wave) * GAME_CONFIG.ENEMIES.bossSpeedMult,
      type: "MINI_BOSS",
      maxHp: scaledBossHp,
      currentHp: scaledBossHp
    });
  }

  const laneCount = Math.max(6, Math.min(10, count + 2));
  const laneIndices: number[] = [];
  for (let l = 0; l < laneCount; l++) {
    laneIndices.push(l);
  }
  for (let k = laneIndices.length - 1; k > 0; k--) {
    const j = Math.floor(Math.random() * (k + 1));
    [laneIndices[k], laneIndices[j]] = [laneIndices[j], laneIndices[k]];
  }

  const laneWidth = 74 / laneCount;

  for (let i = 0; i < count; i++) {
    const v = wordAt(words, cursor + i);
    const lane = laneIndices[i % laneCount];

    const randomJitterX = (Math.random() - 0.5) * (laneWidth * 0.8);
    const posX = 13 + lane * laneWidth + randomJitterX;

    const randomJitterY = Math.random() * 6;
    const posY = -8 - i * GAME_CONFIG.ENEMIES.enemySpawnSpacingY - randomJitterY;

    waveTargets.push({
      id: nextTargetId(`target-w${wave}`),
      ...wordFields(v),
      posX: Math.max(12, Math.min(88, posX)),
      posY: posY,
      speed: baseSpeedForWave(wave),
      type: i % 4 === 0 ? "MONSTER_FAST" : i % 3 === 0 ? "SPACE_MINE" : "MONSTER_NORMAL"
    });
  }

  return waveTargets;
}

/** Mini-boss do đối thủ gửi sang: từ và HP lấy từ AIR_ATTACK. */
function attackBossTarget(attack: AirAttackView, wave: number): TargetWord {
  const hp = Math.max(1, Math.round(attack.bossHp || 1));
  return {
    id: nextTargetId("attack"),
    ...wordFields(attack.word),
    posX: 28 + Math.random() * 44,
    posY: -18,
    speed: baseSpeedForWave(wave) * GAME_CONFIG.ENEMIES.bossSpeedMult,
    type: "MINI_BOSS",
    maxHp: hp,
    currentHp: hp,
    fromAttack: true,
    fromName: attack.fromName
  };
}

function withWeakWord(list: WeakWord[], t: TargetWord, note: string): WeakWord[] {
  const idx = list.findIndex((w) => w.wordId === t.wordId);
  if (idx >= 0) {
    const next = [...list];
    next[idx] = { ...next[idx], note, count: next[idx].count + 1 };
    return next;
  }
  return [
    ...list,
    {
      wordId: t.wordId,
      prompt: t.prompt,
      answer: t.answer,
      kanji: t.kanji,
      reading: t.reading,
      meaning: t.meaning,
      hanViet: t.hanViet,
      note,
      count: 1
    }
  ];
}

export interface WaveTransitionInfo {
  active: boolean;
  phase: "cleared" | "warp" | "incoming" | "none";
  clearedWave: number;
  incomingWave: number;
  isBoss: boolean;
}

export interface MatchIntroState {
  active: boolean;
  phase: "boot" | "warpin" | "ready" | "done";
}

const NO_TRANSITION: WaveTransitionInfo = { active: false, phase: "none", clearedWave: 0, incomingWave: 1, isBoss: false };
const NO_INTRO: MatchIntroState = { active: false, phase: "done" };

/** Chặn bước nhảy quá lớn khi tab bị ẩn rồi quay lại (≈ 4 khung hình). */
const MAX_TICK_DELTA = 4;

interface AirDefenseState {
  // Kết nối & trận
  bootStatus: BootStatus;
  bootError: string | null;
  /** Trạng thái trận lúc đang ở màn cài đặt (chưa có match). */
  setupView: AirStateView | null;
  liveStatus: LiveStatus;
  match: MatchInfo | null;
  players: AirPlayerView[];
  outcome: MatchOutcome;
  result: ResultSummary | null;
  aborted: boolean;
  paused: boolean;
  notice: string | null;

  screen: Screen;
  mode: GameMode;
  wave: number;
  hp: number;
  maxHp: number;
  shield: number;
  score: number;
  combo: number;
  bestCombo: number;
  creditsEarned: number;
  hyperBeamCharge: number;
  remainingRerolls: number;
  /** Số lần bắn trúng nhờ gõ đúng (tính cả từng HP của boss). */
  hits: number;
  wrongInputs: number;
  /** Mốc combo đã gửi đòn trong chuỗi combo hiện tại (đối kháng). */
  usedMilestones: number[];

  targets: TargetWord[];
  lootItems: LootItem[];
  floatingTexts: FloatingText[];
  activeAugments: AugmentCard[];
  draftAugments: AugmentCard[];
  weakWords: WeakWord[];
  pendingAttacks: AirAttackView[];
  banner: ArenaBanner | null;

  dangerZoneActive: boolean;
  screenShake: boolean;
  inboundBoss: boolean;
  lastLaserTarget: { x: number; y: number } | null;
  typingBulletCount: number;
  typingBulletTarget: { x: number; y: number } | null;
  activeComboMilestone: ComboMilestoneData | null;
  comboBreakActive: boolean;
  hyperBeamActive: boolean;
  hyperBeamPhase: "idle" | "charge" | "firing" | "cooldown";
  waveTransition: WaveTransitionInfo;
  isTransitioning: boolean;
  introState: MatchIntroState;
  bgmEnabled: boolean;
  isSettingsOpen: boolean;
  audioSettings: AudioSettings;
  freezeTimer: number;

  setBootError: (message: string) => void;
  showSetup: (state: AirStateView) => void;
  setLiveStatus: (status: LiveStatus) => void;
  setNotice: (message: string | null) => void;
  initMatch: (state: AirStateView) => void;
  syncFromServer: (state: AirStateView) => void;
  applyPlayers: (players: AirPlayerView[]) => void;
  applyPlayer: (view: AirPlayerView) => void;
  applyPresence: (userId: number, online: boolean) => void;
  receiveAttack: (attack: AirAttackView) => void;
  showBanner: (banner: Omit<ArenaBanner, "id">, durationMs?: number) => void;
  showResult: (summary: ResultSummary | null) => void;
  markTimeUp: () => void;
  setOutcome: (outcome: MatchOutcome) => void;

  openSettings: () => void;
  closeSettings: () => void;
  updateAudioSettings: (settings: Partial<AudioSettings>) => void;
  toggleBgm: () => void;
  submitAnswer: (input: string) => boolean;
  aimTypingBullet: (input: string) => void;
  fireTypingBullet: (targetPos?: { x: number; y: number } | null) => void;
  fireHyperBeam: () => void;
  selectAugment: (augment: AugmentCard) => void;
  rerollAugments: () => void;
  tickGameLoop: (delta: number) => void;
  advanceToNextWave: () => void;
  spawnLoot: (x: number, y: number, isBoss?: boolean, allowHyperOrb?: boolean) => void;
}

export function getComboMilestoneData(combo: number, posX?: number, posY?: number): ComboMilestoneData | null {
  if (combo < 5) return null;
  const id = Date.now() + Math.random();
  if (combo === 5) {
    return { milestone: 5, title: "COMBO ×5", subtitle: "NÓNG TAY · +15% ĐIỂM", tone: "gold", id, x: posX, y: posY };
  }
  if (combo === 10) {
    return { milestone: 10, title: "COMBO ×10", subtitle: "QUÁ NHANH · +30% ĐIỂM", tone: "violet", id, x: posX, y: posY };
  }
  if (combo === 15) {
    return { milestone: 15, title: "COMBO ×15", subtitle: "KHÔNG TRƯỢT PHÁT NÀO · +45% ĐIỂM", tone: "violet", id, x: posX, y: posY };
  }
  if (combo === 20) {
    return { milestone: 20, title: "COMBO ×20", subtitle: "BẬC THẦY GÕ PHÍM · +60% ĐIỂM", tone: "rose", id, x: posX, y: posY };
  }
  if (combo === 25) {
    return { milestone: 25, title: "COMBO ×25", subtitle: "KHÔNG AI CẢN NỔI · +75% ĐIỂM", tone: "rose", id, x: posX, y: posY };
  }
  if (combo >= 30 && (combo === 30 || combo % 10 === 0 || combo % 5 === 0)) {
    return { milestone: combo, title: `COMBO ×${combo}`, subtitle: "ĐIỂM THƯỞNG TỐI ĐA", tone: "cyan", id, x: posX, y: posY };
  }
  return null;
}

/** Báo cáo gửi /report và /finish: đúng những số server hiển thị cho đối thủ. */
export function buildReport(state: Pick<AirDefenseState, "hp" | "maxHp" | "score" | "combo" | "bestCombo" | "wave" | "wrongInputs">): AirReport {
  const maxHp = Math.max(1, Math.round(state.maxHp));
  return {
    hp: Math.max(0, Math.min(maxHp, Math.round(state.hp))),
    maxHp,
    score: Math.max(0, Math.round(state.score)),
    combo: Math.max(0, state.combo),
    bestCombo: Math.max(state.bestCombo, state.combo),
    wave: Math.max(1, state.wave),
    wrongInputs: Math.max(0, state.wrongInputs)
  };
}

// ----------------------------------------------------------------------------
// Hẹn giờ trong trận (xoá sạch khi trận kết thúc để không đổi màn hình sau khi có kết quả)
// ----------------------------------------------------------------------------

const gameTimers = new Set<ReturnType<typeof setTimeout>>();

function later(fn: () => void, ms: number) {
  const id = setTimeout(() => {
    gameTimers.delete(id);
    fn();
  }, ms);
  gameTimers.add(id);
}

function clearGameTimers() {
  gameTimers.forEach((id) => clearTimeout(id));
  gameTimers.clear();
}

let introTimer: ReturnType<typeof setInterval> | null = null;

function stopIntroTicker() {
  if (introTimer !== null) {
    clearInterval(introTimer);
    introTimer = null;
  }
}

/** Intro 3 pha kết thúc đúng lúc `startAt` (đồng hồ server): boot → warpin → ready → địch xuất hiện. */
function introPhaseFor(remainingMs: number): MatchIntroState["phase"] {
  if (remainingMs <= 0) return "done";
  if (remainingMs <= 1200) return "ready";
  if (remainingMs <= 2500) return "warpin";
  return "boot";
}

function startIntroTicker(startAt: number) {
  stopIntroTicker();
  const update = () => {
    const store = useAirDefenseStore.getState();
    const phase = introPhaseFor(startAt - serverNow());
    if (phase === "done") {
      stopIntroTicker();
      useAirDefenseStore.setState({ introState: NO_INTRO });
      return;
    }
    if (!store.introState.active || store.introState.phase !== phase) {
      useAirDefenseStore.setState({ introState: { active: true, phase } });
    }
  };
  update();
  introTimer = setInterval(update, 100);
}

const seenAttackIds = new Set<string>();
let bannerSerial = 0;
let bannerTimer: ReturnType<typeof setTimeout> | null = null;

function isBattleScreen(screen: Screen): boolean {
  return screen === "endless" || screen === "pvp";
}

function simulationLive(s: AirDefenseState): boolean {
  return !!s.match && s.outcome === "playing" && !s.paused && isBattleScreen(s.screen) && !s.introState.active;
}

const noop = () => {};

export const useAirDefenseStore = create<AirDefenseState>((set, get) => {
  /** HP về 0: dừng mô phỏng, matchController gửi /finish?defeated=true. */
  const defeat = () => {
    const s = get();
    if (s.outcome !== "playing") return;
    clearGameTimers();
    soundManager.switchBgm("lobby");
    set({
      hp: 0,
      outcome: "defeated",
      combo: 0,
      paused: false,
      isSettingsOpen: false,
      dangerZoneActive: false,
      screenShake: false,
      comboBreakActive: false,
      activeComboMilestone: null,
      lastLaserTarget: null,
      hyperBeamPhase: "idle",
      hyperBeamActive: false,
      isTransitioning: false,
      waveTransition: NO_TRANSITION,
      screen: isBattleScreen(s.screen) ? s.screen : s.mode
    });
    matchHooks.onDefeated?.();
  };

  return {
    bootStatus: "loading",
    bootError: null,
    setupView: null,
    liveStatus: "idle",
    match: null,
    players: [],
    outcome: "playing",
    result: null,
    aborted: false,
    paused: false,
    notice: null,

    screen: "endless",
    mode: "endless",
    wave: 1,
    hp: DEFAULT_SHIP.hp,
    maxHp: DEFAULT_SHIP.hp,
    shield: GAME_CONFIG.PLAYER.baseShield,
    score: 0,
    combo: 0,
    bestCombo: 0,
    creditsEarned: 0,
    hyperBeamCharge: 0,
    remainingRerolls: GAME_CONFIG.ENEMIES.defaultRerolls,
    hits: 0,
    wrongInputs: 0,
    usedMilestones: [],

    targets: [],
    lootItems: [],
    floatingTexts: [],
    activeAugments: [],
    draftAugments: [],
    weakWords: [],
    pendingAttacks: [],
    banner: null,

    dangerZoneActive: false,
    screenShake: false,
    inboundBoss: false,
    lastLaserTarget: null,
    typingBulletCount: 0,
    typingBulletTarget: null,
    activeComboMilestone: null,
    comboBreakActive: false,
    hyperBeamActive: false,
    hyperBeamPhase: "idle",
    waveTransition: NO_TRANSITION,
    isTransitioning: false,
    introState: NO_INTRO,
    bgmEnabled: soundManager.getSettings().bgmEnabled && soundManager.getSettings().masterEnabled,
    isSettingsOpen: false,
    audioSettings: soundManager.getSettings(),
    freezeTimer: 0,

    setBootError: (message) => set({ bootStatus: "error", bootError: message }),
    showSetup: (state) => {
      soundManager.switchBgm("lobby");
      set({ bootStatus: "setup", bootError: null, setupView: state });
    },
    setLiveStatus: (status) => set({ liveStatus: status }),
    setNotice: (message) => set({ notice: message }),

    // ------------------------------------------------------------------ vào trận
    initMatch: (state) => {
      clearGameTimers();
      stopIntroTicker();

      const versus = state.mode === "VERSUS" || !!state.config.versus;
      const mode: GameMode = versus ? "pvp" : "endless";
      const match: MatchInfo = {
        sessionId: state.sessionId,
        playMode: versus ? "VERSUS" : "SOLO",
        versus,
        meId: state.meUserId,
        roomId: state.roomId,
        deckId: state.deckId,
        deckTitle: state.deckTitle,
        questionField: state.config.questionField,
        answerField: state.config.answerField,
        questionLabel: state.config.questionLabel,
        answerLabel: state.config.answerLabel,
        attackMilestones: state.config.attackMilestones ?? [],
        startAt: state.startAt,
        endsAt: state.endsAt,
        words: state.words ?? []
      };
      const me = state.players.find((p) => p.userId === state.meUserId) ?? null;

      const common = {
        bootStatus: "ready" as BootStatus,
        bootError: null,
        setupView: null,
        match,
        players: state.players,
        mode,
        result: null,
        aborted: false,
        paused: false,
        notice: null,
        isSettingsOpen: false,
        shield: GAME_CONFIG.PLAYER.baseShield,
        combo: 0,
        creditsEarned: 0,
        hyperBeamCharge: 0,
        remainingRerolls: GAME_CONFIG.ENEMIES.defaultRerolls,
        hits: 0,
        usedMilestones: [],
        lootItems: [],
        floatingTexts: [],
        activeAugments: [],
        draftAugments: [],
        weakWords: [],
        pendingAttacks: [],
        banner: null,
        dangerZoneActive: false,
        screenShake: false,
        lastLaserTarget: null,
        activeComboMilestone: null,
        comboBreakActive: false,
        hyperBeamActive: false,
        hyperBeamPhase: "idle" as const,
        waveTransition: NO_TRANSITION,
        isTransitioning: false,
        introState: NO_INTRO,
        freezeTimer: 0
      };

      const meStats = {
        wave: Math.max(1, me?.wave ?? 1),
        maxHp: Math.max(1, me?.maxHp ?? DEFAULT_SHIP.hp),
        hp: Math.max(0, me?.hp ?? DEFAULT_SHIP.hp),
        score: Math.max(0, me?.score ?? 0),
        bestCombo: Math.max(0, me?.bestCombo ?? 0),
        wrongInputs: Math.max(0, me?.wrongInputs ?? 0)
      };

      // Trận đã xong (tải lại trang sau khi kết thúc) → thẳng màn tổng kết.
      if (state.result || state.status === "FINISHED" || state.status === "ABORTED") {
        soundManager.switchBgm("lobby");
        set({
          ...common,
          ...meStats,
          targets: [],
          inboundBoss: false,
          screen: "debrief",
          outcome: "over",
          result: state.result,
          aborted: !state.result
        });
        return;
      }

      if (match.words.length === 0) {
        set({ bootStatus: "error", bootError: "Bộ thẻ của trận này không có từ nào để chơi." });
        return;
      }

      const now = serverNow();
      const started = now >= state.startAt;
      const outcome: MatchOutcome = me?.finished
        ? "defeated"
        : state.endsAt !== null && now >= state.endsAt
          ? "timeUp"
          : "playing";

      // Tải lại giữa trận: khôi phục HP/điểm/đợt đã báo lên server; combo bắt đầu lại từ 0.
      const restored = started && me ? meStats : { wave: 1, maxHp: DEFAULT_SHIP.hp, hp: DEFAULT_SHIP.hp, score: 0, bestCombo: 0, wrongInputs: 0 };
      const wave = restored.wave;
      const hp = Math.min(restored.maxHp, restored.hp);
      const introPhase = outcome === "playing" ? introPhaseFor(state.startAt - now) : "done";

      set({
        ...common,
        ...restored,
        hp,
        screen: mode,
        outcome,
        targets: outcome === "playing" ? generateWave(wave, match.words) : [],
        inboundBoss: outcome === "playing" && isBossWave(wave),
        introState: introPhase === "done" ? NO_INTRO : { active: true, phase: introPhase }
      });

      if (outcome !== "playing") {
        soundManager.switchBgm("lobby");
        return;
      }
      if (hp <= 0) {
        defeat();
        return;
      }

      soundManager.switchBgm(isBossWave(wave) ? "boss" : "battle");
      if (state.startAt - now > 150) {
        soundManager.playIntroLaunch();
        startIntroTicker(state.startAt);
      }
    },

    syncFromServer: (state) => {
      if (Array.isArray(state.players)) get().applyPlayers(state.players);
      if (state.result) {
        get().showResult(state.result);
      } else if (state.status === "ABORTED") {
        get().showResult(null);
      }
    },

    applyPlayers: (players) => set({ players }),

    applyPlayer: (view) => {
      const players = get().players;
      const idx = players.findIndex((p) => p.userId === view.userId);
      if (idx >= 0) {
        const next = [...players];
        next[idx] = view;
        set({ players: next });
      } else {
        set({ players: [...players, view].sort((a, b) => a.slot - b.slot) });
      }
    },

    applyPresence: (userId, online) => {
      const players = get().players;
      if (!players.some((p) => p.userId === userId)) return;
      set({ players: players.map((p) => (p.userId === userId ? { ...p, online } : p)) });
    },

    receiveAttack: (attack) => {
      if (!attack || !attack.word || seenAttackIds.has(attack.attackId)) return;
      seenAttackIds.add(attack.attackId);
      const s = get();
      if (!s.match || s.outcome !== "playing") return;
      soundManager.playBossSiren();
      get().showBanner({
        kind: "incoming",
        title: `${attack.fromName} gửi một con trùm sang hành tinh của bạn!`,
        detail: `Combo ×${attack.milestone} · trùm có ${attack.bossHp} máu`
      }, 4200);
      set({ pendingAttacks: [...s.pendingAttacks, attack] });
    },

    showBanner: (banner, durationMs = 3200) => {
      bannerSerial += 1;
      const id = bannerSerial;
      set({ banner: { ...banner, id } });
      if (bannerTimer) clearTimeout(bannerTimer);
      bannerTimer = setTimeout(() => {
        bannerTimer = null;
        if (get().banner?.id === id) set({ banner: null });
      }, durationMs);
    },

    showResult: (summary) => {
      const s = get();
      if (s.outcome === "over" && (s.result || summary === null)) return;
      clearGameTimers();
      stopIntroTicker();
      soundManager.switchBgm("lobby");
      set({
        outcome: "over",
        result: summary,
        aborted: summary === null,
        screen: "debrief",
        paused: false,
        isSettingsOpen: false,
        targets: [],
        lootItems: [],
        floatingTexts: [],
        pendingAttacks: [],
        dangerZoneActive: false,
        screenShake: false,
        comboBreakActive: false,
        activeComboMilestone: null,
        lastLaserTarget: null,
        hyperBeamPhase: "idle",
        hyperBeamActive: false,
        isTransitioning: false,
        waveTransition: NO_TRANSITION,
        introState: NO_INTRO,
        inboundBoss: false
      });
    },

    markTimeUp: () => {
      const s = get();
      if (s.outcome !== "playing" || !s.match) return;
      clearGameTimers();
      stopIntroTicker();
      soundManager.switchBgm("lobby");
      set({
        outcome: "timeUp",
        screen: s.mode,
        paused: false,
        dangerZoneActive: false,
        screenShake: false,
        comboBreakActive: false,
        lastLaserTarget: null,
        hyperBeamPhase: "idle",
        hyperBeamActive: false,
        isTransitioning: false,
        waveTransition: NO_TRANSITION,
        introState: NO_INTRO
      });
      matchHooks.onTimeUp?.();
    },

    setOutcome: (outcome) => set({ outcome, paused: false, isSettingsOpen: outcome === "playing" ? get().isSettingsOpen : false }),

    // ------------------------------------------------------------------ menu & âm thanh
    openSettings: () => {
      const s = get();
      // Chơi đơn không giới hạn thời gian được tạm dừng tại chỗ; có đồng hồ (server chốt giờ) hoặc đối kháng thì không.
      set({
        isSettingsOpen: true,
        paused: s.match?.playMode === "SOLO" && s.match.endsAt === null && s.outcome === "playing"
      });
    },
    closeSettings: () => set({ isSettingsOpen: false, paused: false }),

    updateAudioSettings: (newSettings) => {
      soundManager.updateSettings(newSettings);
      const updated = soundManager.getSettings();
      set({
        audioSettings: updated,
        bgmEnabled: updated.bgmEnabled && updated.masterEnabled
      });
    },

    toggleBgm: () => {
      const curr = get().audioSettings;
      get().updateAudioSettings({ bgmEnabled: !curr.bgmEnabled });
    },

    // ------------------------------------------------------------------ sóng địch
    spawnLoot: (x: number, y: number, isBoss = false, allowHyperOrb = true) => {
      const { lootItems } = get();
      const newItems: LootItem[] = [];
      const count = isBoss ? GAME_CONFIG.LOOT.bossLootMultiplier : 1 + (Math.random() < 0.4 ? 1 : 0);

      for (let i = 0; i < count; i++) {
        let type: LootItemType = "CREDIT_CRYSTAL";
        const roll = Math.random();
        if (roll < GAME_CONFIG.LOOT.repairDropChance) {
          type = "REPAIR_PACK";
        } else if (allowHyperOrb && roll < GAME_CONFIG.LOOT.repairDropChance + GAME_CONFIG.LOOT.hyperOrbDropChance) {
          type = "HYPER_ORB";
        }

        const angle = Math.random() * Math.PI * 2;
        const speed = 0.4 + Math.random() * 0.8;
        const val =
          type === "CREDIT_CRYSTAL"
            ? Math.floor(Math.random() * (GAME_CONFIG.LOOT.creditValueMax - GAME_CONFIG.LOOT.creditValueMin)) + GAME_CONFIG.LOOT.creditValueMin
            : type === "REPAIR_PACK"
            ? GAME_CONFIG.LOOT.repairValue
            : GAME_CONFIG.LOOT.hyperOrbCharge;

        newItems.push({
          id: `loot-${Date.now()}-${Math.random()}`,
          type,
          x: Math.max(10, Math.min(90, x + (Math.random() * 8 - 4))),
          y: Math.max(5, Math.min(90, y + (Math.random() * 6 - 3))),
          vx: Math.cos(angle) * speed,
          vy: Math.sin(angle) * speed * 0.5 + 0.3,
          value: val,
          collected: false,
          spawnTime: Date.now()
        });
      }

      set({ lootItems: [...lootItems, ...newItems] });
    },

    advanceToNextWave: () => {
      const { wave, score, isTransitioning, screen, outcome, match } = get();
      if (!match || outcome !== "playing" || isTransitioning || !isBattleScreen(screen)) return;

      const nextWave = wave + 1;
      const isBossNext = isBossWave(nextWave);

      soundManager.playWaveClear();
      set({
        isTransitioning: true,
        score: score + 1000 * wave,
        waveTransition: {
          active: true,
          phase: "cleared",
          clearedWave: wave,
          incomingWave: nextWave,
          isBoss: isBossNext
        }
      });

      later(() => {
        if (get().outcome === "over") return;
        soundManager.playWarpDrive();
        set({
          waveTransition: {
            active: true,
            phase: "warp",
            clearedWave: wave,
            incomingWave: nextWave,
            isBoss: isBossNext
          }
        });
      }, 900);

      later(() => {
        if (get().outcome === "over") return;
        if (isBossNext) {
          soundManager.playBossSiren();
          soundManager.switchBgm("boss");
        } else {
          soundManager.switchBgm("battle");
        }
        set({
          waveTransition: {
            active: true,
            phase: "incoming",
            clearedWave: wave,
            incomingWave: nextWave,
            isBoss: isBossNext
          }
        });
      }, 1800);

      later(() => {
        const current = get();
        if (current.outcome === "over" || !current.match) return;
        if (wave % GAME_CONFIG.ENEMIES.augmentDraftInterval === 0) {
          const draft = [...AUGMENTS_POOL].sort(() => 0.5 - Math.random()).slice(0, 3);
          soundManager.switchBgm("lobby");
          set({
            screen: "augment",
            draftAugments: draft,
            wave: nextWave,
            inboundBoss: isBossNext,
            isTransitioning: false,
            waveTransition: { active: false, phase: "none", clearedWave: wave, incomingWave: nextWave, isBoss: false }
          });
        } else {
          const nextTargets = generateWave(nextWave, current.match.words);
          set({
            wave: nextWave,
            targets: nextTargets,
            inboundBoss: isBossNext,
            isTransitioning: false,
            waveTransition: { active: false, phase: "none", clearedWave: wave, incomingWave: nextWave, isBoss: false }
          });
        }
      }, 2800);
    },

    // ------------------------------------------------------------------ bắn
    submitAnswer: (input) => {
      const s = get();
      const match = s.match;
      if (!match || s.outcome !== "playing" || s.paused || !isBattleScreen(s.screen) || s.introState.active) return false;
      if (!input.trim()) return false;
      if (s.isTransitioning) return false;

      const { targets, score, combo, bestCombo, creditsEarned, wave, hyperBeamCharge, activeAugments, advanceToNextWave, spawnLoot } = s;

      // 1. Tìm mọi địch có đáp án khớp (luật giống hệt GameAnswerMatcher của server).
      const matchingIndices: number[] = [];
      targets.forEach((t, idx) => {
        if (!t.isDead && matchesAnswer(match.answerField, t.answer, input)) {
          matchingIndices.push(idx);
        }
      });

      if (matchingIndices.length > 0) {
        // 2. Tự động khóa mục tiêu Laser vào quái gần đáy nhất (highest posY)
        matchingIndices.sort((a, b) => targets[b].posY - targets[a].posY);
        const targetIdx = matchingIndices[0];
        const hit = targets[targetIdx];
        const updatedTargets = [...targets];
        let isKilled = true;

        if (hit.type === "MINI_BOSS" && (hit.currentHp || 1) > 1) {
          const remainingHp = (hit.currentHp || 1) - 1;
          updatedTargets[targetIdx] = { ...hit, currentHp: remainingHp };
          isKilled = false;
        } else {
          updatedTargets[targetIdx] = { ...hit, isDead: true };
        }

        // Server kiểm lại lần bắn này (không chờ phản hồi trước khi bắn laser).
        postKill(match.sessionId, hit.wordId, input).catch(noop);

        const newCombo = combo + 1;
        const newBest = Math.max(bestCombo, newCombo);

        // 3. Tính điểm số kèm combo
        let scoreDelta = (GAME_CONFIG.SCORING.baseScorePerTarget + newCombo * (GAME_CONFIG.SCORING.baseScorePerTarget * GAME_CONFIG.SCORING.comboBonusMultiplier)) * wave;
        if (hit.type === "MINI_BOSS" && isKilled) {
          scoreDelta += GAME_CONFIG.SCORING.bossScoreBonus;
        }
        if (activeAugments.some((a) => a.id === "DUAL_CANNON")) {
          scoreDelta = Math.round(scoreDelta * GAME_CONFIG.SCORING.dualCannonScoreBonus);
        }

        let creditDelta = GAME_CONFIG.SCORING.baseCreditsPerTarget + wave * GAME_CONFIG.SCORING.creditsWaveMultiplier;
        if (hit.type === "MINI_BOSS" && isKilled) {
          creditDelta += GAME_CONFIG.SCORING.bossCreditsBonus;
        }
        if (activeAugments.some((a) => a.id === "GOLD_MAGNET")) {
          creditDelta = Math.round(creditDelta * GAME_CONFIG.SCORING.goldMagnetCoinBonus);
        }

        soundManager.playLaser();
        if (isKilled) {
          setTimeout(() => soundManager.playExplosion(), 80);
          spawnLoot(hit.posX, Math.max(10, hit.posY), hit.type === "MINI_BOSS");
        }
        soundManager.playComboDing(newCombo);

        let chargeDelta = GAME_CONFIG.PLAYER.hyperBeamChargePerHit;
        if (isKilled) {
          chargeDelta = hit.type === "MINI_BOSS" ? GAME_CONFIG.PLAYER.hyperBeamChargePerBossKill : GAME_CONFIG.PLAYER.hyperBeamChargePerKill;
        }

        // 4. Lõi CHRONO_FREEZE: đóng băng 4 giây sau mỗi 5 combo
        let newFreeze = get().freezeTimer;
        if (newCombo > 0 && newCombo % 5 === 0 && activeAugments.some((a) => a.id === "CHRONO_FREEZE")) {
          newFreeze = Math.max(newFreeze, 4.0);
        }

        // 5. Floating Combat Number (+Điểm kèm Combo Multiplier)
        const hitColor = newCombo >= 20 ? "#ff4d6d" : newCombo >= 10 ? "#c3a6ff" : newCombo >= 5 ? "#ffc857" : "#55f4ff";
        const extraFloating: FloatingText[] = [
          ...get().floatingTexts,
          {
            id: `hit-${Date.now()}-${Math.random()}`,
            text: `+${scoreDelta.toLocaleString()} [×${newCombo}]`,
            color: hitColor,
            x: hit.posX,
            y: Math.max(8, hit.posY - 4),
            opacity: 1,
            life: 1.2
          }
        ];

        // 6. Combo Milestones Feedback (5, 10, 15, 20, 25, 30+)
        const milestoneData = getComboMilestoneData(newCombo, hit.posX, Math.max(8, hit.posY));

        if (milestoneData) {
          soundManager.playComboMilestone(milestoneData.milestone);
        }

        // 7. Đối kháng: mốc combo 10/15/20 gửi mini-boss sang đối thủ (mỗi mốc một lần trong một chuỗi combo).
        let usedMilestones = s.usedMilestones;
        if (match.versus && match.attackMilestones.includes(newCombo) && !usedMilestones.includes(newCombo)) {
          usedMilestones = [...usedMilestones, newCombo];
          postAttack(match.sessionId, newCombo)
            .then((res) => {
              if (res && res.sent) {
                get().showBanner({ kind: "sent", title: "Đã gửi trùm sang đối thủ!", detail: res.message });
              }
            })
            .catch(noop);
        }

        const shouldShake = hit.type === "MINI_BOSS" || !!milestoneData || (newCombo >= 5 && newCombo % 5 === 0);

        set({
          targets: updatedTargets,
          score: Math.round(score + scoreDelta),
          combo: newCombo,
          bestCombo: newBest,
          hits: s.hits + 1,
          usedMilestones,
          creditsEarned: creditsEarned + creditDelta,
          hyperBeamCharge: Math.min(GAME_CONFIG.PLAYER.hyperBeamMaxCharge, hyperBeamCharge + chargeDelta),
          lastLaserTarget: { x: hit.posX, y: Math.max(5, hit.posY) },
          activeComboMilestone: milestoneData ? milestoneData : get().activeComboMilestone,
          screenShake: shouldShake ? true : get().screenShake,
          freezeTimer: newFreeze,
          floatingTexts: extraFloating
        });

        later(() => {
          set({ lastLaserTarget: null });
        }, GAME_CONFIG.VISUALS.laserBeamDurationMs);

        if (shouldShake) {
          later(() => {
            set({ screenShake: false });
          }, 400);
        }

        if (milestoneData) {
          later(() => {
            set({ activeComboMilestone: null });
          }, 1500);
        }

        const aliveLeft = updatedTargets.filter((t) => !t.isDead).length;
        if (aliveLeft === 0 && get().pendingAttacks.length === 0) {
          advanceToNextWave();
        }

        return true;
      }

      // Gõ sai: đứt combo, ghi từ gần phòng tuyến nhất vào danh sách cần ôn.
      const activeTargets = targets.filter((t) => !t.isDead);
      const missed = activeTargets.sort((a, b) => b.posY - a.posY)[0];
      const newWeakWords = missed ? withWeakWord(s.weakWords, missed, "Gõ sai") : s.weakWords;

      const prevCombo = s.combo;
      if (prevCombo >= 5) {
        soundManager.playComboBreak();
        set({ combo: 0, usedMilestones: [], wrongInputs: s.wrongInputs + 1, weakWords: newWeakWords, comboBreakActive: true, activeComboMilestone: null });
        later(() => set({ comboBreakActive: false }), 600);
      } else {
        set({ combo: 0, usedMilestones: [], wrongInputs: s.wrongInputs + 1, weakWords: newWeakWords, activeComboMilestone: null });
      }

      return false;
    },

    aimTypingBullet: (input) => {
      const s = get();
      const match = s.match;
      if (!match || !input.trim() || !simulationLive(s)) return;
      const matchedTarget = s.targets.find((t) => !t.isDead && isPartialAnswer(match.answerField, t.answer, input, t.reading));
      if (matchedTarget) get().fireTypingBullet({ x: matchedTarget.posX, y: matchedTarget.posY });
    },

    fireTypingBullet: (targetPos) => set((state) => ({ typingBulletCount: state.typingBulletCount + 1, typingBulletTarget: targetPos || null })),

    fireHyperBeam: () => {
      const s = get();
      if (!simulationLive(s) || s.hyperBeamPhase !== "idle") return;
      if (s.hyperBeamCharge < GAME_CONFIG.PLAYER.hyperBeamMaxCharge) return;

      // Giai đoạn 1: Khoảng nghỉ nạp tụ năng lượng cực đại (900ms)
      soundManager.playHyperBeamCharge();
      set({
        hyperBeamCharge: 0,
        hyperBeamPhase: "charge",
        hyperBeamActive: false,
        screenShake: false
      });

      // Giai đoạn 2: Khai hỏa chùm siêu Laser cực đại (Duy trì 3.0 giây)
      later(() => {
        if (get().outcome === "over") return;
        soundManager.playHyperBeam();

        const { targets, wave, score, spawnLoot } = get();
        const updatedTargets: TargetWord[] = [];

        targets.forEach((t) => {
          if (t.isDead) return;

          if (t.type === "MINI_BOSS") {
            const currentHp = t.currentHp || 1;
            const damage = GAME_CONFIG.PLAYER.hyperBeamBossDamage;
            if (currentHp <= damage) {
              updatedTargets.push({ ...t, currentHp: 0, isDead: true });
              spawnLoot(t.posX, Math.max(10, t.posY), true, false);
            } else {
              updatedTargets.push({ ...t, currentHp: currentHp - damage, isDead: false });
              spawnLoot(t.posX, Math.max(10, t.posY), false, false);
            }
          } else {
            updatedTargets.push({ ...t, isDead: true });
            spawnLoot(t.posX, Math.max(10, t.posY), false, false);
          }
        });

        set({
          targets: updatedTargets,
          score: score + 500 * wave,
          screenShake: true,
          hyperBeamPhase: "firing",
          hyperBeamActive: true
        });

        // Giai đoạn 3: Kết thúc chùm tia & Khoảng chờ hồi phục (1.0 giây)
        later(() => {
          set({
            hyperBeamPhase: "cooldown",
            hyperBeamActive: false,
            screenShake: false
          });

          // Giai đoạn 4: Hoàn tất sau 1.0s cooldown -> Chuyển wave nếu sạch quái
          later(() => {
            set({ hyperBeamPhase: "idle" });
            const current = get();
            const aliveLeft = current.targets.filter((t) => !t.isDead).length;
            if (aliveLeft === 0 && current.pendingAttacks.length === 0) {
              current.advanceToNextWave();
            }
          }, GAME_CONFIG.PLAYER.hyperBeamCooldownMs);
        }, GAME_CONFIG.PLAYER.hyperBeamDurationMs);
      }, GAME_CONFIG.PLAYER.hyperBeamChargeTimeMs);
    },

    // ------------------------------------------------------------------ augment
    selectAugment: (augment) => {
      const { activeAugments, wave, hp, maxHp, shield, match, outcome, mode, screen } = get();
      if (!match || outcome !== "playing" || screen !== "augment") return;
      const updatedAugments = [...activeAugments, augment];

      let newHp = hp;
      let newShield = shield;
      if (augment.id === "REPAIR_NANO") {
        newHp = Math.min(maxHp, Math.round(hp + maxHp * 0.3));
      } else if (augment.id === "SHIELD_BARRIER") {
        newShield += 3;
      }

      const nextTargets = generateWave(wave, match.words);
      const isBoss = isBossWave(wave);

      soundManager.switchBgm(isBoss ? "boss" : "battle");

      set({
        screen: mode,
        activeAugments: updatedAugments,
        hp: newHp,
        shield: newShield,
        targets: nextTargets,
        inboundBoss: isBoss,
        isTransitioning: false
      });
    },

    rerollAugments: () => {
      const { remainingRerolls } = get();
      if (remainingRerolls <= 0) return;
      const draft = [...AUGMENTS_POOL].sort(() => 0.5 - Math.random()).slice(0, 3);
      set({
        remainingRerolls: remainingRerolls - 1,
        draftAugments: draft
      });
    },

    // ------------------------------------------------------------------ vòng lặp 60 FPS
    tickGameLoop: (rawDelta) => {
      let s = get();
      if (!simulationLive(s)) return;
      const match = s.match!;

      // Có đồng hồ: dừng mô phỏng đúng lúc endsAt, server chốt kết quả rồi gửi SESSION_FINISHED.
      if (match.endsAt !== null && serverNow() >= match.endsAt) {
        get().markTimeUp();
        return;
      }

      const delta = Math.max(0, Math.min(MAX_TICK_DELTA, rawDelta));

      // 0. Mini-boss đối thủ gửi sang (chờ hết chuyển đợt / hyper beam rồi mới thả)
      if (s.pendingAttacks.length > 0 && !s.isTransitioning && s.hyperBeamPhase === "idle") {
        const spawned = s.pendingAttacks.map((a) => attackBossTarget(a, s.wave));
        set({ targets: [...s.targets, ...spawned], pendingAttacks: [] });
        s = get();
      }

      // Phase Hyper Beam Firing: Quét sạch quái thường và trừ HP Boss
      if (s.hyperBeamPhase === "firing") {
        const updatedTargets = s.targets.map((t) => {
          if (t.isDead) return t;
          if (t.type === "MINI_BOSS") {
            const bossHp = Math.max(0, (t.currentHp || 1) - GAME_CONFIG.PLAYER.hyperBeamBossDamage);
            return { ...t, currentHp: bossHp, isDead: bossHp === 0 };
          }
          return { ...t, isDead: true };
        });
        set({ targets: updatedTargets });
        s = get();
      }

      // Kiểm tra trạng thái đóng băng (Chrono Freeze)
      const isFrozen = s.freezeTimer > 0;
      if (isFrozen) {
        set({ freezeTimer: Math.max(0, s.freezeTimer - delta / 60) });
        s = get();
      }

      // 1. Tick Enemy Movement & Collisions
      if (!s.isTransitioning) {
        let hasDanger = false;
        let damageTaken = 0;
        const remainingTargets: TargetWord[] = [];
        let missedList: WeakWord[] = s.weakWords;
        const hasCryo = s.activeAugments.some((a) => a.id === "CRYO_PAYLOAD");
        const speedMult = (isFrozen ? 0 : 1) * (hasCryo ? 0.65 : 1.0);

        s.targets.forEach((t) => {
          if (t.isDead) return;

          const newY = t.posY + t.speed * delta * speedMult;
          if (newY >= GAME_CONFIG.PLAYER.dangerZoneThreshold) {
            hasDanger = true;
          }

          if (newY >= 100) {
            damageTaken += t.type === "MINI_BOSS" ? GAME_CONFIG.PLAYER.damagePerEnemyReachBottom * 2 : GAME_CONFIG.PLAYER.damagePerEnemyReachBottom;
            missedList = withWeakWord(missedList, t, t.type === "MINI_BOSS" ? "Trùm chạm vạch" : "Chạm phòng tuyến");
          } else {
            remainingTargets.push({ ...t, posY: newY });
          }
        });

        if (damageTaken > 0) {
          soundManager.playDamage();
          let nextShield = s.shield;
          let nextHp = s.hp;

          if (nextShield > 0) {
            nextShield = Math.max(0, nextShield - 1);
          } else {
            nextHp = Math.max(0, nextHp - damageTaken);
          }

          let shouldBreak = false;
          if (s.combo >= 5) {
            shouldBreak = true;
            soundManager.playComboBreak();
            later(() => set({ comboBreakActive: false }), 600);
          }

          set({
            hp: nextHp,
            shield: nextShield,
            combo: 0,
            usedMilestones: [],
            activeComboMilestone: null,
            comboBreakActive: shouldBreak,
            targets: remainingTargets,
            weakWords: missedList,
            dangerZoneActive: hasDanger,
            screenShake: true
          });

          later(() => {
            set({ screenShake: false });
          }, 300);

          if (nextHp <= 0) {
            defeat();
            return;
          }
        } else {
          set({
            targets: remainingTargets,
            dangerZoneActive: hasDanger
          });
        }

        const current = get();
        if (remainingTargets.length === 0 && current.pendingAttacks.length === 0 && !current.isTransitioning && current.hyperBeamPhase === "idle") {
          current.advanceToNextWave();
        }
      }

      // 2. Tick Loot Items Movement & Magnetic Absorption
      s = get();
      if (s.lootItems.length > 0) {
        const playerX = 50;
        const playerY = 85;
        const nextLoot: LootItem[] = [];
        const nextFloating: FloatingText[] = [...s.floatingTexts];
        let creditAdd = 0;
        let hpAdd = 0;
        let beamAdd = 0;

        s.lootItems.forEach((item) => {
          if (item.collected) return;

          const vx = (item.vx || 0) * 0.92;
          const vy = (item.vy || 0.4) * 0.92;

          let nx = item.x + vx * delta;
          let ny = item.y + vy * delta;

          const dx = playerX - nx;
          const dy = playerY - ny;
          const dist = Math.sqrt(dx * dx + dy * dy);

          // Continuous accelerating magnetic attraction toward player ship
          if (dist > 0.001) {
            const magnetSpeed = Math.min(3.6, 0.5 + (1 - Math.min(1, dist / 80)) * 3.1) * delta;
            nx += (dx / dist) * magnetSpeed;
            ny += (dy / dist) * magnetSpeed;
          }

          // Absorption when reaching player radius
          if (dist < 6 || ny >= 85) {
            soundManager.playItemCollect(item.type);

            if (item.type === "CREDIT_CRYSTAL") {
              creditAdd += item.value;
              nextFloating.push({
                id: `ft-${Date.now()}-${Math.random()}`,
                text: `+${item.value} CREDITS`,
                color: "#ffc857",
                x: playerX,
                y: playerY - 4,
                opacity: 1,
                life: 1
              });
            } else if (item.type === "REPAIR_PACK") {
              hpAdd += item.value;
              nextFloating.push({
                id: `ft-${Date.now()}-${Math.random()}`,
                text: `+${item.value} HP`,
                color: "#55f4ff",
                x: playerX,
                y: playerY - 4,
                opacity: 1,
                life: 1
              });
            } else {
              beamAdd += item.value;
              nextFloating.push({
                id: `ft-${Date.now()}-${Math.random()}`,
                text: `+${item.value}% HYPER BEAM`,
                color: "#c3a6ff",
                x: playerX,
                y: playerY - 4,
                opacity: 1,
                life: 1
              });
            }
          } else if (ny < 105) {
            nextLoot.push({ ...item, x: nx, y: ny, vx, vy });
          }
        });

        set({
          lootItems: nextLoot,
          floatingTexts: nextFloating,
          creditsEarned: s.creditsEarned + creditAdd,
          hp: Math.min(s.maxHp, s.hp + hpAdd),
          hyperBeamCharge: s.hyperBeamPhase === "idle" ? Math.min(100, s.hyperBeamCharge + beamAdd) : 0
        });
      }

      // 3. Tick Floating Texts
      s = get();
      if (s.floatingTexts.length > 0) {
        const updatedFloating: FloatingText[] = [];
        s.floatingTexts.forEach((ft) => {
          const nextLife = ft.life - 0.025 * delta;
          if (nextLife > 0) {
            updatedFloating.push({
              ...ft,
              y: ft.y - 0.25 * delta,
              opacity: nextLife,
              life: nextLife
            });
          }
        });
        set({ floatingTexts: updatedFloating });
      }
    }
  };
});
