import React, { useCallback, useEffect, useRef, useState } from 'react';
import { arena, errorMessage, errorStatus, hasArena, nekoApi, serverClock } from '../arena/client';
import type {
  LiveStatus,
  NekoActionResult,
  NekoBonus,
  NekoMistakeView,
  NekoPlayerView,
  NekoRoundView,
  NekoStartOptions,
  DeckCardView,
  NekoStateView,
  PlayMode,
  PresencePayload,
  ResultSummary,
  SessionFinishedPayload,
  ShopView,
} from '../arena/types';
import type { SkinOption } from './PregameCard';
import { soundManager } from '../audio/SoundManager';
import { CatSpriteSheet, type CatSkinId, SKIN_CODES, skinFromCode, registerShopSkins } from '../graphics/CatSpriteSheet';
import { ParallaxBackground } from '../graphics/ParallaxBackground';
import { ParticleSystem } from '../graphics/ParticleSystem';
import { SpriteRenderer } from '../graphics/SpriteRenderer';
import { DISPLAY_FONT_STACK, fieldFont, isJapaneseField, VI_FONT_STACK } from '../lib/text';
import { answerFishSize, MOBILE_GAME_MAX_WIDTH, playfieldSize } from '../lib/playfield';
import { fallSpeedMultiplier, stepFallingObjects } from '../lib/fallingObjects';
import { gameBackUrl } from '../../../shared/gameNavigation';
import type { AnswerItemType, BonusItemType, CatPlayer, FallingObject } from '../types/game';
import { HelpModal } from './HelpModal';
import {
  ClockIcon as Clock,
  HeartIcon as Heart,
  MaximizeIcon as Maximize,
  MinimizeIcon as Minimize,
  SettingsIcon as Settings,
  SparklesIcon as Sparkles,
  TrophyIcon as Trophy,
  Volume2Icon as Volume2,
} from './Icons';
import { MenuModal } from './MenuModal';
import { finishReasonLabel, OpponentPanel } from './OpponentPanel';
import { PregameCard } from './PregameCard';
import { ResultScreen } from './ResultScreen';

/** Read by main.tsx from the data attributes of #neko-root (templates/neko/board.html). */
export interface HostConfig {
  sessionId: string;
  mode: PlayMode;
  backUrl: string;
  roomId?: string | null;
  meId: number | null;
  /** Trang phục đang dùng (chọn ở Cửa hàng); trong trận không đổi được. */
  skin: CatSkinId;
}

type Phase = 'loading' | 'error' | 'pregame' | 'playing' | 'waiting' | 'result' | 'aborted';
type ActionKind = 'catch' | 'miss' | 'bonus';

interface FloatingText {
  x: number;
  y: number;
  text: string;
  color: string;
  alpha: number;
  vy: number;
  size: number;
}

interface Reveal {
  id: number;
  prompt: string;
  answer: string;
}

const ANSWER_TYPES: AnswerItemType[] = ['fish_blue', 'fish_pink', 'fish_green', 'paw_cookie', 'fish_blue', 'fish_pink'];
const BONUS_ITEMS: Record<NekoBonus, BonusItemType> = {
  GOLDEN_FISH: 'golden_fish',
  CLOCK: 'clock',
  SHIELD: 'shield',
  FREEZE: 'freeze',
};
/** Fall speed in px per 60 fps frame (the original values, 30% slower than the first prototype). */
const BASE_SPEED: Record<string, number> = { EASY: 1.12, NORMAL: 1.68, HARD: 2.38 };
const FRAME_MS = 1000 / 60;
/** FREEZE: the server adds 2 s to the deadline; the client stops falling items and its timer for 2 s. */
const FREEZE_MS = 2000;
const TIME_UP_RETRY_MS = 1500;
const WAITING_POLL_MS = 4000;
const OFFLINE_POLL_MS = 5000;
/** A catch/miss reply that takes longer than this unlocks the round and re-syncs (fetch has no timeout). */
const LOCK_TIMEOUT_MS = 8000;

// Animations always play, whatever the device's reduced-motion setting (project rule).
const prefersReducedMotion = () => false;

function roundFor(state: NekoStateView, me: NekoPlayerView): NekoRoundView | null {
  const count = state.rounds.length;
  if (count === 0) return null;
  return state.rounds[((me.roundIndex % count) + count) % count];
}

function promptSizeClass(text: string): string {
  const length = Array.from(text).length;
  if (length <= 6) return 'text-3xl sm:text-4xl';
  if (length <= 12) return 'text-2xl sm:text-3xl';
  if (length <= 24) return 'text-xl sm:text-2xl';
  return 'text-base sm:text-lg';
}

const panelStyle: React.CSSProperties = {
  background: 'linear-gradient(160deg, rgba(30,15,70,0.97), rgba(15,5,40,0.99))',
  border: '2px solid rgba(255,159,67,0.4)',
  boxShadow: '0 0 60px rgba(255,159,67,0.25)',
};

const Spinner: React.FC = () => (
  <span
    aria-hidden="true"
    className="inline-block w-8 h-8 rounded-full border-4 border-amber-300/25 border-t-amber-300 animate-spin"
  />
);

// ─── Main Component ────────────────────────────────────────
export const NekoCatchGame: React.FC<{ host: HostConfig }> = ({ host }) => {
  const sessionId = host.sessionId;
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const screenDimRef = useRef({ w: window.innerWidth, h: window.innerHeight });

  // ── Server data (mirrored into refs for the rAF loop)
  const [state, setState] = useState<NekoStateView | null>(null);
  const [me, setMe] = useState<NekoPlayerView | null>(null);
  const [opponent, setOpponent] = useState<NekoPlayerView | null>(null);
  const [summary, setSummary] = useState<ResultSummary | null>(null);
  const [mistakes, setMistakes] = useState<NekoMistakeView[]>([]);
  const [fatalError, setFatalError] = useState<string | null>(null);
  const [phase, setPhase] = useState<Phase>('loading');
  const [activeRound, setActiveRound] = useState<NekoRoundView | null>(null);
  const [presentChoices, setPresentChoices] = useState<string[]>([]);
  const [roundLocked, setRoundLocked] = useState(false);

  // ── Timers & effect UI
  const [pregameCountdown, setPregameCountdown] = useState<number | null>(null);
  const [resumeCountdown, setResumeCountdown] = useState<number | string | null>(null);
  const [goFlash, setGoFlash] = useState(false);
  const [timeLeft, setTimeLeft] = useState(0);
  const [effectStatus, setEffectStatus] = useState({ freezeMs: 0, shield: false });
  const [reveal, setReveal] = useState<Reveal | null>(null);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [pausedUi, setPausedUi] = useState(false);

  // ── Chrome
  const [menuOpen, setMenuOpen] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);
  const [skin, setSkin] = useState<CatSkinId>(host.skin);
  const [assetsReady, setAssetsReady] = useState(false);
  const [assetError, setAssetError] = useState<string | null>(null);
  const [assetRetry, setAssetRetry] = useState(0);
  const assetsReadyRef = useRef(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [mobile, setMobile] = useState(() => window.innerWidth <= MOBILE_GAME_MAX_WIDTH);
  const [liveStatus, setLiveStatus] = useState<LiveStatus>('idle');
  const [audioLocked, setAudioLocked] = useState(() => soundManager.isLocked());
  const [replaying, setReplaying] = useState(false);
  const [replayError, setReplayError] = useState<string | null>(null);

  // ── Refs read by the game loop and the async handlers
  const stateRef = useRef<NekoStateView | null>(null);
  const meRef = useRef<NekoPlayerView | null>(null);
  /** serverTime of the newest NekoPlayerView applied for me (replies can arrive out of order). */
  const meStampRef = useRef(0);
  const opponentRef = useRef<NekoPlayerView | null>(null);
  const summaryRef = useRef<ResultSummary | null>(null);
  const abortedRef = useRef(false);
  const fatalErrorRef = useRef<string | null>(null);
  const phaseRef = useRef<Phase>('loading');
  const startedRef = useRef(false);
  const activeRoundRef = useRef<NekoRoundView | null>(null);
  /** Catch/miss awaiting the server: the round is locked until the reply decides it. */
  const lockRef = useRef<{ roundId: string; at: number } | null>(null);
  const missedWhileLockedRef = useRef(false);
  const bonusInFlightRef = useRef(0);
  const spawnedBonusRef = useRef(new Set<string>());
  const fallingRef = useRef<FallingObject[]>([]);
  /** Server-clock ms until which FREEZE holds the falling items and the timer. */
  const freezeUntilRef = useRef(0);
  /** Local simulation halted: SOLO paused on the server, pause request in flight or resume countdown. */
  const pausedRef = useRef(false);
  const pauseInFlightRef = useRef(false);
  const resumingRef = useRef(false);
  const resumeTimerRef = useRef<number | null>(null);
  const timeUpSentAtRef = useRef(0);
  const finishedHandledRef = useRef(false);
  const resultHandledRef = useRef(false);
  const pregameCountRef = useRef<number | null>(null);
  const countdownBeepRef = useRef(false);
  const timeLeftRef = useRef(-1);
  const lastEffectUiRef = useRef(0);
  const fetchingRef = useRef(false);
  const refetchQueuedRef = useRef<boolean | null>(null);
  const menuOpenRef = useRef(false);
  const helpOpenRef = useRef(false);
  const liveWasOnlineRef = useRef(false);
  const toastTimerRef = useRef<number | null>(null);
  const revealTimerRef = useRef<number | null>(null);
  // Late-bound handlers (declared further down; the callbacks above them call through these refs).
  const requestPauseRef = useRef<() => Promise<void>>(async () => {});
  const loadStateRef = useRef<(resetRound: boolean) => Promise<void>>(async () => {});
  const sendActionRef = useRef<(kind: ActionKind, roundId: string, choiceId: string | null, at: { x: number; y: number }) => void>(
    () => {}
  );

  const catRef = useRef<CatPlayer>({
    x: window.innerWidth / 2,
    y: window.innerHeight - 120,
    width: 88,
    height: 96,
    speed: 10,
    state: 'idle',
    frameIndex: 0,
    basketWidth: 72,
    basketHeight: 32,
    basketOffsetX: 0,
    basketOffsetY: -8,
    shieldActive: false,
  });
  const mouseTargetXRef = useRef(window.innerWidth / 2);
  const keyStateRef = useRef({ left: false, right: false });
  const bgRef = useRef<ParallaxBackground | null>(null);
  if (!bgRef.current) bgRef.current = new ParallaxBackground();
  const particlesRef = useRef(new ParticleSystem());
  const floatingTextsRef = useRef<FloatingText[]>([]);
  const shakeRef = useRef({ duration: 0, intensity: 0 });

  const myId = useCallback((): number => stateRef.current?.meUserId ?? host.meId ?? -1, [host.meId]);
  const isSolo = useCallback(() => (stateRef.current?.mode ?? host.mode) === 'SOLO', [host.mode]);

  // ──────────────────────────────────────────────────────────
  // Small UI helpers
  // ──────────────────────────────────────────────────────────
  const showToast = useCallback((message: string, type: 'success' | 'error' = 'error') => {
    setToast({ message, type });
    if (toastTimerRef.current) window.clearTimeout(toastTimerRef.current);
    toastTimerRef.current = window.setTimeout(() => setToast(null), 3500);
  }, []);

  const showReveal = useCallback((prompt: string, answer: string) => {
    setReveal({ id: Date.now(), prompt, answer });
    if (revealTimerRef.current) window.clearTimeout(revealTimerRef.current);
    revealTimerRef.current = window.setTimeout(() => setReveal(null), 1900);
  }, []);

  const addFloatingText = useCallback((x: number, y: number, text: string, color: string, size = 24, vy = -1.7) => {
    floatingTextsRef.current.push({ x, y, text, color, alpha: 1, vy, size });
  }, []);

  const triggerShake = useCallback((intensity: number, duration: number) => {
    if (prefersReducedMotion()) return;
    shakeRef.current = { duration, intensity };
  }, []);

  const setCatFeedback = useCallback((next: 'catch_success' | 'catch_wrong', ms: number) => {
    const cat = catRef.current;
    if (cat.state === 'win' || cat.state === 'lose') return;
    cat.state = next;
    window.setTimeout(() => {
      if (catRef.current.state === next) catRef.current.state = 'idle';
    }, ms);
  }, []);

  const updatePresentChoices = useCallback(() => {
    setPresentChoices(
      fallingRef.current.filter(item => !item.isBonus && item.choiceId).map(item => item.choiceId as string)
    );
  }, []);

  const setLock = useCallback((roundId: string | null) => {
    lockRef.current = roundId ? { roundId, at: performance.now() } : null;
    setRoundLocked(!!roundId);
  }, []);

  const clearAnswerFish = useCallback(() => {
    fallingRef.current = fallingRef.current.filter(item => item.isBonus);
    updatePresentChoices();
  }, [updatePresentChoices]);

  const clearField = useCallback(() => {
    setLock(null);
    missedWhileLockedRef.current = false;
    fallingRef.current = [];
    activeRoundRef.current = null;
    setActiveRound(null);
    setPresentChoices([]);
  }, [setLock]);

  const openMenuUi = useCallback(() => {
    menuOpenRef.current = true;
    setMenuOpen(true);
  }, []);

  // ──────────────────────────────────────────────────────────
  // Timer: per-player deadline on the server clock
  // ──────────────────────────────────────────────────────────
  const remainingMs = useCallback((now: number): number => {
    const st = stateRef.current;
    const mine = meRef.current;
    if (!st || !mine) return 0;
    if (st.awaitingStart) return st.config.durationSeconds * 1000;
    let at = now;
    if (st.status === 'PAUSED' && st.pausedAt) at = st.pausedAt;
    else if (!startedRef.current && now < st.startAt) at = st.startAt;
    at = Math.max(at, freezeUntilRef.current);
    return Math.max(0, mine.deadlineAt - at);
  }, []);

  // ──────────────────────────────────────────────────────────
  // Spawning: fish come from the server round, never decided locally
  // ──────────────────────────────────────────────────────────
  const spawnRound = useCallback(
    (round: NekoRoundView) => {
      const st = stateRef.current;
      if (!st) return;
      const { w, h } = screenDimRef.current;
      const field = playfieldSize(w, h);
      const count = Math.max(1, round.choices.length);
      const spacing = w / (count + 1);
      const { width, height } = answerFishSize(w, count);
      // Narrow screens: alternate two rows so neighbouring cards do not cover each other's text.
      const staggered = spacing < width * 0.95;
      const baseSpeed = BASE_SPEED[st.config.difficulty] ?? BASE_SPEED.NORMAL;
      const speedMultiplier = fallSpeedMultiplier(meRef.current?.roundIndex ?? 0);
      const japanese = isJapaneseField(st.config.answerField);

      const fish: FallingObject[] = round.choices.map((choice, i) => ({
        id: `${round.id}:${choice.id}`,
        x: Math.max(width / 2 + 4, Math.min(w - width / 2 - 4, spacing * (i + 1) + (Math.random() * 20 - 10))),
        y: -30 - i * 20 - Math.random() * 20 - (staggered && i % 2 === 1 ? height + 26 : 0),
        speed: (baseSpeed + Math.random() * 0.3) * speedMultiplier,
        width,
        height,
        type: ANSWER_TYPES[i % ANSWER_TYPES.length],
        roundId: round.id,
        choiceId: choice.id,
        text: choice.text,
        japanese,
        rotation: (Math.random() - 0.5) * 0.14,
        rotSpeed: (Math.random() - 0.5) * 0.008,
      }));

      // Bonus items still falling from earlier rounds stay on screen (they keep their own roundId).
      const bonuses = fallingRef.current.filter(item => item.isBonus && item.y < field.missY);
      if (round.bonus && !spawnedBonusRef.current.has(round.id)) {
        const type = BONUS_ITEMS[round.bonus];
        if (type) {
          spawnedBonusRef.current.add(round.id);
          fish.push({
            id: `${round.id}:bonus`,
            x: Math.random() * Math.max(1, w - 240) + Math.min(120, w / 2),
            y: -90,
            speed: 1.4 * speedMultiplier,
            width: field.bonusSize,
            height: field.bonusSize,
            type,
            roundId: round.id,
            choiceId: null,
            text: '',
            japanese: false,
            isBonus: true,
            bonusType: type,
            rotation: 0,
            rotSpeed: 0.025,
          });
        }
      }

      fallingRef.current = [...fish, ...bonuses];
      activeRoundRef.current = round;
      setActiveRound(round);
      updatePresentChoices();
    },
    [updatePresentChoices]
  );

  /** Puts the player's current server round (rounds[me.roundIndex % n]) on screen when it is not there yet. */
  const syncRound = useCallback(() => {
    const st = stateRef.current;
    const mine = meRef.current;
    if (!st || !mine || phaseRef.current !== 'playing') return;
    if (mine.finished || st.status === 'FINISHED' || st.status === 'ABORTED') return;
    if (lockRef.current) return;
    const target = roundFor(st, mine);
    if (!target) return;
    const active = activeRoundRef.current;
    const hasFish = fallingRef.current.some(item => !item.isBonus);
    if (!active || active.id !== target.id || !hasFish) spawnRound(target);
  }, [spawnRound]);

  // ──────────────────────────────────────────────────────────
  // Phase machine
  // ──────────────────────────────────────────────────────────
  const playResultEffects = useCallback(
    (silent: boolean) => {
      if (resultHandledRef.current) return;
      resultHandledRef.current = true;
      const result = summaryRef.current;
      const won = !!result && result.winnerUserId !== null && result.winnerUserId === myId();
      const draw = !!result?.draw;
      catRef.current.state = won || draw ? 'win' : 'lose';
      if (silent) return;
      if (won) soundManager.playSFX('victory');
      else if (draw) soundManager.playSFX('levelup');
      else if (meRef.current?.finishReason !== 'LIVES') soundManager.playSFX('wrong');
    },
    [myId]
  );

  const refreshPhase = useCallback(() => {
    const st = stateRef.current;
    const mine = meRef.current;
    // Reload after the end (or a state re-fetch that beat SESSION_FINISHED): the state carries the summary.
    if (!summaryRef.current && st?.status === 'FINISHED' && st.result) {
      summaryRef.current = st.result;
      setSummary(st.result);
    }
    let next: Phase;
    if (abortedRef.current || st?.status === 'ABORTED') next = 'aborted';
    else if (summaryRef.current) next = 'result';
    else if (fatalErrorRef.current) next = 'error';
    else if (!st || !mine) next = 'loading';
    else if (!assetsReadyRef.current && !st.awaitingStart) next = 'loading';
    else if (st.status === 'FINISHED') next = 'aborted';
    else if (mine.finished) next = 'waiting';
    else if (!startedRef.current && (st.awaitingStart || serverClock.now() < st.startAt)) next = 'pregame';
    else next = 'playing';

    const prev = phaseRef.current;
    if (next === prev) return;
    phaseRef.current = next;
    setPhase(next);

    if (next === 'playing') {
      const fresh = !startedRef.current;
      startedRef.current = true;
      if (fresh && prev === 'pregame') {
        setGoFlash(true);
        window.setTimeout(() => setGoFlash(false), 750);
      }
      catRef.current.state = 'idle';
      if (!pausedRef.current) soundManager.playBGM();
      syncRound();
      // SOLO: a dialog left open over the countdown pauses the match right away.
      if (isSolo() && (menuOpenRef.current || helpOpenRef.current) && st?.status === 'RUNNING') {
        openMenuUi();
        void requestPauseRef.current();
      }
    } else if (next === 'waiting' || next === 'result' || next === 'aborted' || next === 'error') {
      soundManager.stopBGM();
      if (next !== 'waiting') {
        clearField();
        menuOpenRef.current = false;
        setMenuOpen(false);
      }
      if (next === 'result') playResultEffects(prev === 'loading');
    }
  }, [clearField, isSolo, openMenuUi, playResultEffects, syncRound]);

  // ──────────────────────────────────────────────────────────
  // Applying server views
  // ──────────────────────────────────────────────────────────
  const onMeFinished = useCallback(
    (view: NekoPlayerView, quiet: boolean) => {
      finishedHandledRef.current = true;
      clearField();
      soundManager.stopBGM();
      const cat = catRef.current;
      const audible = !quiet && phaseRef.current !== 'loading';
      if (view.finishReason === 'LIVES') {
        cat.state = 'lose';
        if (audible) {
          soundManager.playSFX('wrong');
          soundManager.playSFX('impact');
        }
        addFloatingText(cat.x, cat.y - 90, 'HẾT TIM!', '#FF4757', 30, -1.2);
      } else if (view.finishReason === 'TIME') {
        cat.state = 'idle';
        addFloatingText(cat.x, cat.y - 90, 'HẾT GIỜ!', '#FFD700', 30, -1.2);
      }
      refreshPhase();
      // Mistakes (and the result for SOLO) come with the next state; SESSION_FINISHED usually arrives first.
      window.setTimeout(() => void loadStateRef.current(false), 700);
    },
    [addFloatingText, clearField, refreshPhase]
  );

  const applyMe = useCallback(
    (view: NekoPlayerView, stamp: number, force = false, quiet = false): boolean => {
      const current = meRef.current;
      if (!force && current) {
        if (stamp < meStampRef.current) return false;
        if (current.finished && !view.finished) return false;
      }
      meStampRef.current = Math.max(meStampRef.current, stamp);
      meRef.current = view;
      catRef.current.shieldActive = view.shield && !view.finished;
      setMe(view);
      if (view.finished && !finishedHandledRef.current) onMeFinished(view, quiet);
      return true;
    },
    [onMeFinished]
  );

  const mergeOpponent = useCallback((view: NekoPlayerView, force: boolean) => {
    const current = opponentRef.current;
    if (!force && current && current.userId === view.userId) {
      // SSE may reorder: never step the opponent HUD backwards.
      if (current.finished && !view.finished) return;
      if (view.roundIndex < current.roundIndex) return;
      if (view.roundIndex === current.roundIndex && view.correct + view.wrong < current.correct + current.wrong) return;
    }
    opponentRef.current = view;
    setOpponent(view);
  }, []);

  const handleSummary = useCallback(
    (result: ResultSummary) => {
      if (summaryRef.current) return;
      summaryRef.current = result;
      setSummary(result);
      refreshPhase();
    },
    [refreshPhase]
  );

  const handleAborted = useCallback(() => {
    if (abortedRef.current || summaryRef.current) return;
    abortedRef.current = true;
    refreshPhase();
  }, [refreshPhase]);

  const applyState = useCallback(
    (view: NekoStateView, resetRound: boolean) => {
      serverClock.observe(view.serverTime);
      const prev = stateRef.current;
      // Resume shifts every deadline by the pause length; a FREEZE that was running shifts with it.
      if (prev && prev.status === 'PAUSED' && view.status !== 'PAUSED' && prev.pausedAt && freezeUntilRef.current > prev.pausedAt) {
        freezeUntilRef.current += Math.max(0, view.serverTime - prev.pausedAt);
      }
      stateRef.current = view;
      setState(view);
      setMistakes(view.myMistakes ?? []);

      const mine = view.players.find(player => player.userId === view.meUserId) ?? null;
      const other = view.players.find(player => player.userId !== view.meUserId) ?? null;
      if (!mine) {
        fatalErrorRef.current = 'Bạn không tham gia trận đấu này.';
        setFatalError(fatalErrorRef.current);
        refreshPhase();
        return;
      }
      if (other) mergeOpponent(other, !opponentRef.current);
      if (resetRound) {
        setLock(null);
        missedWhileLockedRef.current = false;
        activeRoundRef.current = null;
      }
      pausedRef.current = view.status === 'PAUSED' || pauseInFlightRef.current || resumingRef.current;
      setPausedUi(view.status === 'PAUSED');
      applyMe(mine, view.serverTime);

      if (view.status === 'FINISHED' && view.result) handleSummary(view.result);
      else if (view.status === 'ABORTED') handleAborted();
      refreshPhase();

      // SOLO paused elsewhere (another tab, auto-pause on a hidden tab): show the menu so it can be resumed.
      if (view.status === 'PAUSED' && phaseRef.current === 'playing' && !resumingRef.current && !menuOpenRef.current) {
        soundManager.pauseBGM();
        openMenuUi();
      }
      syncRound();
    },
    [applyMe, handleAborted, handleSummary, mergeOpponent, openMenuUi, refreshPhase, setLock, syncRound]
  );

  const loadState = useCallback(
    async (resetRound: boolean) => {
      if (!sessionId || !hasArena()) return;
      if (fetchingRef.current) {
        refetchQueuedRef.current = (refetchQueuedRef.current ?? false) || resetRound;
        return;
      }
      fetchingRef.current = true;
      try {
        const view = await nekoApi.state(sessionId);
        applyState(view, resetRound);
      } catch (error) {
        const status = errorStatus(error);
        const message = errorMessage(error, 'Không tải được trận đấu.');
        if (!stateRef.current || status === 401 || status === 403 || status === 404) {
          fatalErrorRef.current = message;
          setFatalError(message);
          refreshPhase();
        } else {
          showToast(message);
          window.setTimeout(() => void loadStateRef.current(resetRound), 2000);
        }
      } finally {
        fetchingRef.current = false;
        const queued = refetchQueuedRef.current;
        if (queued !== null) {
          refetchQueuedRef.current = null;
          void loadStateRef.current(queued);
        }
      }
    },
    [applyState, refreshPhase, sessionId, showToast]
  );
  loadStateRef.current = loadState;

  // ──────────────────────────────────────────────────────────
  // Actions: catch / miss / bonus — the server decides everything
  // ──────────────────────────────────────────────────────────
  const applyBonusEffect = useCallback(
    (bonus: NekoBonus | null, at: { x: number; y: number }) => {
      if (!bonus) return;
      soundManager.playSFX('levelup');
      particlesRef.current.emitCorrect(at.x, at.y);
      if (bonus === 'GOLDEN_FISH') {
        addFloatingText(at.x, at.y - 20, '+50', '#FFD700', 26, -1.8);
      } else if (bonus === 'CLOCK') {
        addFloatingText(at.x, at.y - 20, '+5 GIÂY', '#00CEC9', 24, -1.8);
      } else if (bonus === 'SHIELD') {
        addFloatingText(at.x, at.y - 20, 'KHIÊN!', '#00B894', 22, -1.8);
      } else if (bonus === 'FREEZE') {
        freezeUntilRef.current = Math.max(freezeUntilRef.current, serverClock.now()) + FREEZE_MS;
        addFloatingText(at.x, at.y - 20, 'ĐÓNG BĂNG 2s!', '#38BDF8', 26, -1.8);
      }
    },
    [addFloatingText]
  );

  const onActionReply = useCallback(
    (kind: ActionKind, round: NekoRoundView | null, roundId: string, reply: NekoActionResult, at: { x: number; y: number }) => {
      serverClock.observe(reply.serverTime);
      if (kind === 'bonus') bonusInFlightRef.current = Math.max(0, bonusInFlightRef.current - 1);
      const ownsLock = kind !== 'bonus' && lockRef.current?.roundId === roundId;

      // The match already ended while this request was in flight: only keep the numbers.
      if (finishedHandledRef.current || summaryRef.current || abortedRef.current) {
        if (ownsLock) setLock(null);
        applyMe(reply.me, reply.serverTime, false, true);
        return;
      }

      if (!reply.accepted) {
        if (ownsLock) setLock(null);
        applyMe(reply.me, reply.serverTime, false, true);
        if (reply.finished || reply.me.finished) {
          // I am done, or the match ended (e.g. the opponent left) without the SSE event reaching us.
          void loadStateRef.current(false);
          return;
        }
        // Stale round: re-fetch and respawn the player's current round. A rejected bonus only re-syncs.
        if (kind !== 'bonus') {
          clearAnswerFish();
          activeRoundRef.current = null;
        }
        void loadStateRef.current(kind !== 'bonus');
        return;
      }

      if (kind === 'bonus') {
        applyBonusEffect(reply.bonusApplied, at);
      } else if (kind === 'catch' && reply.correct) {
        soundManager.playSFX('correct');
        particlesRef.current.emitCorrect(at.x, at.y);
        triggerShake(3, 8);
        setCatFeedback('catch_success', 500);
        // The server just added 10 + floor(combo / 3) * 10 for this catch.
        const points = 10 + Math.floor(reply.me.combo / 3) * 10;
        addFloatingText(at.x, at.y - 20, points > 10 ? `+${points} (COMBO!)` : `+${points}`, '#FFD700', 26, -1.8);
        if (reply.me.combo > 0 && reply.me.combo % 3 === 0) soundManager.playSFX('levelup');
      } else if (reply.shieldBlocked) {
        soundManager.playSFX('levelup');
        addFloatingText(at.x, at.y - 20, 'ĐÃ CHẶN!', '#00B894', 22, -1.6);
      } else {
        soundManager.playSFX('wrong');
        soundManager.playSFX('impact');
        particlesRef.current.emitWrong(at.x, at.y);
        if (kind === 'catch') {
          triggerShake(7, 18);
          setCatFeedback('catch_wrong', 600);
          addFloatingText(at.x, at.y - 20, 'SAI!', '#FF4757', 24, -1.6);
        } else {
          triggerShake(6, 16);
          setCatFeedback('catch_wrong', 700);
          addFloatingText(at.x, at.y - 20, 'LỠ TỪ!', '#FF4757', 24, -1.6);
        }
      }

      if (kind !== 'bonus' && reply.advanced && !reply.correct && reply.correctText && round) {
        showReveal(round.prompt, reply.correctText);
      }

      if (ownsLock) {
        setLock(null);
        const exhaustedWhileLocked = missedWhileLockedRef.current;
        missedWhileLockedRef.current = false;
        if (reply.advanced) {
          clearAnswerFish();
          activeRoundRef.current = null;
        } else if (exhaustedWhileLocked) {
          // A shield blocked the catch, but no answer fish remain to finish this round.
          const { w, h } = screenDimRef.current;
          sendActionRef.current('miss', roundId, null, { x: w / 2, y: h - 100 });
        }
      }

      applyMe(reply.me, reply.serverTime, false, true);
      syncRound();
    },
    [addFloatingText, applyBonusEffect, applyMe, clearAnswerFish, setCatFeedback, setLock, showReveal, syncRound, triggerShake]
  );

  const onActionError = useCallback(
    (kind: ActionKind, roundId: string, error: unknown) => {
      if (kind === 'bonus') {
        bonusInFlightRef.current = Math.max(0, bonusInFlightRef.current - 1);
        showToast(errorMessage(error, 'Không nhận được vật phẩm.'));
        return;
      }
      if (lockRef.current?.roundId === roundId) setLock(null);
      clearAnswerFish();
      activeRoundRef.current = null;
      if (!summaryRef.current && !abortedRef.current) {
        showToast(errorMessage(error, 'Mạng chập chờn, đang thử lại…'));
        void loadStateRef.current(true);
      }
    },
    [clearAnswerFish, setLock, showToast]
  );

  const sendAction = useCallback(
    (kind: ActionKind, roundId: string, choiceId: string | null, at: { x: number; y: number }) => {
      let round: NekoRoundView | null = null;
      if (kind !== 'bonus') {
        round = activeRoundRef.current;
        if (!round || round.id !== roundId || lockRef.current) return;
        setLock(round.id);
        missedWhileLockedRef.current = false;
      } else {
        bonusInFlightRef.current += 1;
      }
      const request =
        kind === 'catch'
          ? nekoApi.catchChoice(sessionId, roundId, choiceId ?? '')
          : kind === 'miss'
            ? nekoApi.miss(sessionId, roundId)
            : nekoApi.bonus(sessionId, roundId);
      request.then(
        reply => onActionReply(kind, round, roundId, reply, at),
        error => onActionError(kind, roundId, error)
      );
    },
    [onActionError, onActionReply, sessionId, setLock]
  );
  sendActionRef.current = sendAction;

  const sendTimeUp = useCallback(() => {
    nekoApi
      .timeUp(sessionId)
      .then(view => applyState(view, false))
      .catch(() => {
        // The server finishes the player on its own shortly after the deadline (NEKO_PROGRESS).
      });
  }, [applyState, sessionId]);

  // ──────────────────────────────────────────────────────────
  // Pause / resume (SOLO only — VERSUS matches cannot pause)
  // ──────────────────────────────────────────────────────────
  const requestPause = useCallback(async () => {
    const st = stateRef.current;
    if (!st || st.mode !== 'SOLO' || st.status !== 'RUNNING' || pauseInFlightRef.current) return;
    if (phaseRef.current !== 'playing' || meRef.current?.finished) return;
    pauseInFlightRef.current = true;
    pausedRef.current = true;
    soundManager.pauseBGM();
    try {
      const view = await nekoApi.pause(sessionId);
      pauseInFlightRef.current = false;
      applyState(view, false);
    } catch (error) {
      pauseInFlightRef.current = false;
      pausedRef.current = stateRef.current?.status === 'PAUSED' || resumingRef.current;
      if (!pausedRef.current && phaseRef.current === 'playing') soundManager.playBGM();
      showToast(errorMessage(error, 'Không tạm dừng được.'));
    }
  }, [applyState, sessionId, showToast]);
  requestPauseRef.current = requestPause;

  const [starting, setStarting] = useState(false);
  const [deckCards, setDeckCards] = useState<DeckCardView[] | null>(null);
  const [skinShop, setSkinShop] = useState<ShopView | null>(null);
  const [skinBusy, setSkinBusy] = useState<string | null>(null);
  const [skinError, setSkinError] = useState<string | null>(null);
  const skinRequestInFlight = useRef(false);
  const skinOptions: SkinOption[] | null = skinShop?.sections.find(section => section.gameType === 'NEKO_CATCH')?.items
    .map(item => ({ ...item, id: skinFromCode(item.code) })) ?? null;
  const awaitingStart = !!state?.awaitingStart;
  const deckId = state?.deckId;
  useEffect(() => {
    if (!awaitingStart || !deckId || deckCards) return;
    let cancelled = false;
    nekoApi.deckCards(deckId)
      .then(cards => { if (!cancelled) setDeckCards(cards); })
      .catch(() => { if (!cancelled) setDeckCards([]); });
    return () => { cancelled = true; };
  }, [awaitingStart, deckId, deckCards]);

  // Màn cài đặt trước trận: có nhạc nền (bị chặn tự phát thì SoundManager phát ngay ở lần bấm đầu tiên).
  useEffect(() => {
    if (awaitingStart) soundManager.playBGM();
  }, [awaitingStart]);

  // Số dư, quyền sở hữu và trang phục đang dùng đều lấy từ phản hồi của Cửa hàng.
  const applySkinShop = useCallback((view: ShopView) => {
    registerShopSkins(view.sections.find(section => section.gameType === 'NEKO_CATCH')?.items ?? []);
    setSkinShop(view);
    const equipped = view.sections.find(section => section.gameType === 'NEKO_CATCH')?.items.find(item => item.equipped);
    if (equipped) setSkin(skinFromCode(equipped.code));
  }, []);
  const reloadSkinShop = useCallback(() => {
    setSkinError(null);
    nekoApi.shop().then(applySkinShop)
      .catch(error => setSkinError(errorMessage(error, 'Không tải được trang phục. Hãy thử lại.')));
  }, [applySkinShop]);
  useEffect(() => {
    if (!awaitingStart) return;
    let cancelled = false;
    nekoApi.shop()
      .then(view => {
        if (!cancelled) applySkinShop(view);
      })
      .catch(error => { if (!cancelled) setSkinError(errorMessage(error, 'Không tải được trang phục. Hãy thử lại.')); });
    return () => { cancelled = true; };
  }, [awaitingStart, applySkinShop]);

  const changeSkin = useCallback(async (next: CatSkinId) => {
    if (!awaitingStart || starting || skinRequestInFlight.current || !skinShop) return false;
    const item = skinShop.sections.find(section => section.gameType === 'NEKO_CATCH')?.items.find(item => item.code === SKIN_CODES[next]);
    if (!item || item.equipped) return false;
    if (!item.owned && skinShop.balance < item.price) return false;
    skinRequestInFlight.current = true;
    setSkinBusy(item.code);
    setSkinError(null);
    soundManager.playSFX('click');
    try {
      applySkinShop(await (item.owned ? nekoApi.equip(item.code) : nekoApi.buy(item.code)));
      showToast(item.owned ? `Đang dùng ${item.name}.` : `Đã mua ${item.name} và đang dùng.`, 'success');
      return true;
    } catch (error) {
      setSkinError(errorMessage(error, 'Không lưu được trang phục. Hãy thử lại.'));
      // Có thể số dư đã thay đổi ở tab khác; không đổi skin trước khi server xác nhận.
      try { applySkinShop(await nekoApi.shop()); } catch { /* Giữ danh sách hiện tại để thử lại. */ }
      return false;
    } finally {
      skinRequestInFlight.current = false;
      setSkinBusy(null);
    }
  }, [awaitingStart, starting, skinShop, applySkinShop, showToast]);
  const startMatch = useCallback(async (options: NekoStartOptions) => {
    if (!stateRef.current?.awaitingStart || skinRequestInFlight.current || !assetsReadyRef.current) return;
    setStarting(true);
    soundManager.playSFX('click');
    try {
      // Đổi số cá thì server tạo lại dãy lượt: bỏ lượt đang giữ để lấy lượt đầu của dãy mới.
      applyState(await nekoApi.start(sessionId, options), true);
    } catch (error) {
      showToast(errorMessage(error, 'Không bắt đầu được, thử lại.'));
    } finally {
      setStarting(false);
    }
  }, [applyState, sessionId, showToast]);

  const cancelResumeCountdown = useCallback(() => {
    if (resumeTimerRef.current) window.clearInterval(resumeTimerRef.current);
    resumeTimerRef.current = null;
    resumingRef.current = false;
    setResumeCountdown(null);
    pausedRef.current = stateRef.current?.status === 'PAUSED' || pauseInFlightRef.current;
  }, []);

  const finishResume = useCallback(async () => {
    try {
      const view = await nekoApi.resume(sessionId);
      resumingRef.current = false;
      applyState(view, false);
      if (!pausedRef.current && phaseRef.current === 'playing') soundManager.playBGM();
    } catch (error) {
      resumingRef.current = false;
      pausedRef.current = stateRef.current?.status === 'PAUSED' || pauseInFlightRef.current;
      showToast(errorMessage(error, 'Không tiếp tục được, thử lại.'));
      if (pausedRef.current) openMenuUi();
    }
  }, [applyState, openMenuUi, sessionId, showToast]);

  /** Same 3-2-1 as the original game; the server stays paused until it ends, so no time is lost. */
  const startResumeCountdown = useCallback(() => {
    if (resumingRef.current) return;
    resumingRef.current = true;
    pausedRef.current = true;
    soundManager.playSFX('countdown');
    let count = 3;
    setResumeCountdown(count);
    resumeTimerRef.current = window.setInterval(() => {
      count -= 1;
      if (count > 0) setResumeCountdown(count);
      else if (count === 0) setResumeCountdown('BẮT ĐẦU!');
      else {
        if (resumeTimerRef.current) window.clearInterval(resumeTimerRef.current);
        resumeTimerRef.current = null;
        setResumeCountdown(null);
        void finishResume();
      }
    }, 750);
  }, [finishResume]);

  const openMenu = useCallback(() => {
    const current = phaseRef.current;
    if (menuOpenRef.current || current === 'loading' || current === 'error' || current === 'result' || current === 'aborted') return;
    if (resumingRef.current) cancelResumeCountdown();
    openMenuUi();
    if (isSolo() && current === 'playing') void requestPause();
  }, [cancelResumeCountdown, isSolo, openMenuUi, requestPause]);

  const closeMenu = useCallback(() => {
    menuOpenRef.current = false;
    setMenuOpen(false);
    const st = stateRef.current;
    if (isSolo() && phaseRef.current === 'playing' && (st?.status === 'PAUSED' || pauseInFlightRef.current)) {
      startResumeCountdown();
    }
  }, [isSolo, startResumeCountdown]);

  const leaveMatch = useCallback(async () => {
    try {
      await nekoApi.leave(sessionId);
    } catch (error) {
      const status = errorStatus(error);
      // The session is already gone: nothing left to forfeit.
      if (status !== 404 && status !== 410) throw error;
    }
    soundManager.stopBGM();
    window.location.href = gameBackUrl(stateRef.current?.roomId || host.roomId, host.backUrl);
  }, [host.backUrl, host.roomId, sessionId]);

  const playAgain = useCallback(async () => {
    const st = stateRef.current;
    if (!st) return;
    setReplaying(true);
    setReplayError(null);
    try {
      const launch = await nekoApi.soloAgain(
        st.deckId,
        st.config.questionField,
        st.config.answerField,
        st.config.difficulty,
        st.config.durationSeconds
      );
      window.location.href = launch.playUrl;
    } catch (error) {
      setReplayError(errorMessage(error, 'Không tạo được trận mới.'));
      setReplaying(false);
    }
  }, []);

  const retryLoad = useCallback(() => {
    fatalErrorRef.current = null;
    setFatalError(null);
    refreshPhase();
    void loadState(true);
  }, [loadState, refreshPhase]);

  // ──────────────────────────────────────────────────────────
  // Frame clock: countdown to startAt, timer, time-up
  // ──────────────────────────────────────────────────────────
  const tickClock = useCallback(
    (now: number) => {
      const st = stateRef.current;
      const mine = meRef.current;
      if (!st || !mine) return;
      if (phaseRef.current === 'pregame' && st.awaitingStart) {
        // Luyện tập: chờ người chơi bấm "Bắt đầu", chưa đếm ngược.
        if (pregameCountRef.current !== null) {
          pregameCountRef.current = null;
          setPregameCountdown(null);
        }
      } else if (phaseRef.current === 'pregame') {
        const msLeft = st.startAt - now;
        if (msLeft <= 0) {
          refreshPhase();
        } else {
          const n = Math.ceil(msLeft / 1000);
          if (n !== pregameCountRef.current) {
            pregameCountRef.current = n;
            setPregameCountdown(n);
          }
          if (!countdownBeepRef.current && msLeft <= 3200 && msLeft > 300) {
            countdownBeepRef.current = true;
            // Arriving late (slow load): start the 3-2-1 sound where the number on screen already is.
            soundManager.playCountdown(Math.max(0, 3200 - msLeft) / 1000);
          }
        }
      }
      const playing = phaseRef.current === 'playing';
      if (!playing && phaseRef.current !== 'pregame') return;

      const remaining = remainingMs(now);
      const seconds = Math.ceil(remaining / 1000);
      if (seconds !== timeLeftRef.current) {
        const previous = timeLeftRef.current;
        timeLeftRef.current = seconds;
        setTimeLeft(seconds);
        if (playing && seconds > 0 && seconds <= 10 && previous > seconds && !pausedRef.current) soundManager.playSFX('click');
      }
      if (
        playing &&
        remaining <= 0 &&
        !mine.finished &&
        st.status === 'RUNNING' &&
        !pausedRef.current &&
        now - timeUpSentAtRef.current >= TIME_UP_RETRY_MS
      ) {
        timeUpSentAtRef.current = now;
        sendTimeUp();
      }
    },
    [refreshPhase, remainingMs, sendTimeUp]
  );

  // ──────────────────────────────────────────────────────────
  // Direct choice (number keys / accessible buttons): move the cat under that fish
  // ──────────────────────────────────────────────────────────
  const selectChoice = useCallback((index: number) => {
    if (screenDimRef.current.w <= MOBILE_GAME_MAX_WIDTH) return;
    const round = activeRoundRef.current;
    const mine = meRef.current;
    if (!round || !mine || mine.finished || phaseRef.current !== 'playing' || pausedRef.current || lockRef.current) return;
    const choice = round.choices[index];
    if (!choice) return;
    const item = fallingRef.current.find(entry => !entry.isBonus && entry.choiceId === choice.id && entry.roundId === round.id);
    if (!item) return;
    const cat = catRef.current;
    cat.x = item.x;
    mouseTargetXRef.current = item.x;
    item.y = cat.y - 22;
  }, []);

  // ──────────────────────────────────────────────────────────
  // Effects: initial load, realtime channel, input, resize…
  // ──────────────────────────────────────────────────────────
  useEffect(() => {
    if (!sessionId) {
      fatalErrorRef.current = 'Không tìm thấy trận đấu. Hãy mở lại từ trang Trò chơi.';
    } else if (!hasArena()) {
      fatalErrorRef.current = 'Không mở được trò chơi. Hãy tải lại trang.';
    }
    if (fatalErrorRef.current) {
      setFatalError(fatalErrorRef.current);
      refreshPhase();
      return;
    }
    void loadState(true);
  }, [loadState, refreshPhase, sessionId]);

  useEffect(() => {
    if (!sessionId || !hasArena()) return;
    const live = arena().live;
    const offs: Array<() => void> = [];
    offs.push(
      live.onStatus(status => {
        if (status === 'online') liveWasOnlineRef.current = true;
        setLiveStatus(status);
      })
    );
    offs.push(live.on('*', () => serverClock.markLive()));
    // Events may have been missed while disconnected: re-fetch (the round is only respawned if it changed).
    offs.push(
      live.on<{ reconnected?: boolean }>('CONNECTED', payload => {
        if (payload && payload.reconnected) void loadStateRef.current(false);
      })
    );
    offs.push(
      live.on<NekoPlayerView>('NEKO_PROGRESS', (payload, envelope) => {
        if (!payload || !envelope || envelope.sessionId !== sessionId) return;
        if (payload.userId === myId()) {
          const current = meRef.current;
          if (payload.finished) {
            // The server finished me (e.g. its clock passed my deadline).
            applyMe(payload, envelope.serverTime, true);
          } else if (
            current &&
            !lockRef.current &&
            bonusInFlightRef.current === 0 &&
            envelope.serverTime > meStampRef.current &&
            payload.roundIndex >= current.roundIndex
          ) {
            const roundChanged = payload.roundIndex !== current.roundIndex;
            applyMe(payload, envelope.serverTime);
            if (roundChanged) {
              activeRoundRef.current = null;
              syncRound();
            }
          }
          return;
        }
        mergeOpponent(payload, false);
      })
    );
    offs.push(
      live.on<PresencePayload>('SESSION_PRESENCE', (payload, envelope) => {
        if (!payload || !envelope || envelope.sessionId !== sessionId || payload.userId === myId()) return;
        const current = opponentRef.current;
        if (current && current.userId === payload.userId && current.online !== payload.online) {
          const next = { ...current, online: payload.online };
          opponentRef.current = next;
          setOpponent(next);
        }
      })
    );
    offs.push(
      live.on<SessionFinishedPayload>('SESSION_FINISHED', (payload, envelope) => {
        if (!envelope || envelope.sessionId !== sessionId) return;
        if (!payload || !payload.summary) {
          handleAborted();
          return;
        }
        for (const view of payload.detail ?? []) {
          if (view.userId === myId()) applyMe(view, envelope.serverTime, true);
          else mergeOpponent(view, true);
        }
        handleSummary(payload.summary);
        // The review list (myMistakes) comes with the state.
        void loadStateRef.current(false);
      })
    );
    live.connect();
    return () => offs.forEach(off => off());
  }, [applyMe, handleAborted, handleSummary, mergeOpponent, myId, sessionId, syncRound]);

  // Waiting for the opponent (or for the SOLO summary): poll in case an SSE event is missed.
  useEffect(() => {
    if (phase !== 'waiting') return;
    const timer = window.setInterval(() => void loadStateRef.current(false), WAITING_POLL_MS);
    return () => window.clearInterval(timer);
  }, [phase]);

  // VERSUS without the realtime channel: keep the opponent HUD roughly fresh.
  useEffect(() => {
    if (phase !== 'playing' || state?.mode !== 'VERSUS' || liveStatus !== 'offline') return;
    const timer = window.setInterval(() => void loadStateRef.current(false), OFFLINE_POLL_MS);
    return () => window.clearInterval(timer);
  }, [liveStatus, phase, state?.mode]);

  // SOLO: leaving the tab pauses the match (the animation loop stops but the server clock would not).
  useEffect(() => {
    const onVisibility = () => {
      if (document.hidden && isSolo() && phaseRef.current === 'playing' && stateRef.current?.status === 'RUNNING') {
        openMenu();
      }
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, [isSolo, openMenu]);

  useEffect(() => {
    menuOpenRef.current = menuOpen;
    helpOpenRef.current = helpOpen;
    if (menuOpen || helpOpen) keyStateRef.current = { left: false, right: false };
  }, [menuOpen, helpOpen]);

  useEffect(() => {
    CatSpriteSheet.setSkin(skin);
    let cancelled = false;
    assetsReadyRef.current = false;
    setAssetsReady(false);
    setAssetError(null);
    void Promise.all([CatSpriteSheet.prepare(skin), bgRef.current!.prepare(), SpriteRenderer.prepare()])
      .then(() => {
        if (cancelled) return;
        assetsReadyRef.current = true;
        setAssetsReady(true);
        refreshPhase();
      })
      .catch(error => { if (!cancelled) setAssetError(errorMessage(error, 'Không tải được hình trò chơi. Hãy thử lại.')); });
    return () => { cancelled = true; };
  }, [skin, assetRetry, refreshPhase]);

  useEffect(() => soundManager.onLockChange(setAudioLocked), []);

  useEffect(() => {
    const onFullscreen = () => setIsFullscreen(!!document.fullscreenElement);
    document.addEventListener('fullscreenchange', onFullscreen);
    return () => document.removeEventListener('fullscreenchange', onFullscreen);
  }, []);

  useEffect(() => {
    const handleResize = () => {
      const w = window.innerWidth;
      const h = window.innerHeight;
      const previous = screenDimRef.current;
      const field = playfieldSize(w, h);
      const cat = catRef.current;
      cat.width = field.catWidth;
      cat.height = field.catHeight;
      cat.y = field.catY;
      cat.basketWidth = 72 * field.scale;
      cat.basketHeight = 32 * field.scale;
      cat.basketOffsetY = -8 * field.scale;
      cat.x = Math.max(field.edge, Math.min(w - field.edge, cat.x * w / previous.w));
      mouseTargetXRef.current = Math.max(field.edge, Math.min(w - field.edge, mouseTargetXRef.current * w / previous.w));
      const fishSize = answerFishSize(w, activeRoundRef.current?.choices.length ?? 1);
      for (const item of fallingRef.current) {
        item.width = item.isBonus ? field.bonusSize : fishSize.width;
        item.height = item.isBonus ? field.bonusSize : fishSize.height;
        item.x = Math.max(item.width / 2 + 4, Math.min(w - item.width / 2 - 4, item.x * w / previous.w));
        item.y *= h / previous.h;
      }
      screenDimRef.current = { w, h };
      setMobile(field.mobile);
      if ((previous.w <= MOBILE_GAME_MAX_WIDTH) !== field.mobile && stateRef.current) {
        void loadStateRef.current(true);
      }
    };
    window.addEventListener('resize', handleResize);
    handleResize();
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  useEffect(
    () => () => {
      soundManager.stopBGM();
      soundManager.stopFreeze();
      if (resumeTimerRef.current) window.clearInterval(resumeTimerRef.current);
      if (toastTimerRef.current) window.clearTimeout(toastTimerRef.current);
      if (revealTimerRef.current) window.clearTimeout(revealTimerRef.current);
    },
    []
  );

  // Keyboard: arrows / A-D move, 1–9 catch the matching fish, Esc opens the menu.
  useEffect(() => {
    const isFormField = (target: EventTarget | null) =>
      target instanceof HTMLElement &&
      (target.tagName === 'INPUT' || target.tagName === 'SELECT' || target.tagName === 'TEXTAREA' || target.isContentEditable);
    const down = (event: KeyboardEvent) => {
      if (menuOpenRef.current || helpOpenRef.current || isFormField(event.target)) return;
      const key = event.key;
      if (key === 'ArrowLeft' || key === 'a' || key === 'A') {
        keyStateRef.current.left = true;
        if (key === 'ArrowLeft') event.preventDefault();
      } else if (key === 'ArrowRight' || key === 'd' || key === 'D') {
        keyStateRef.current.right = true;
        if (key === 'ArrowRight') event.preventDefault();
      } else if (/^[1-9]$/.test(key) && !event.repeat) {
        selectChoice(Number(key) - 1);
      } else if (key === 'Escape') {
        openMenu();
      }
    };
    const up = (event: KeyboardEvent) => {
      const key = event.key;
      if (key === 'ArrowLeft' || key === 'a' || key === 'A') keyStateRef.current.left = false;
      if (key === 'ArrowRight' || key === 'd' || key === 'D') keyStateRef.current.right = false;
    };
    const blur = () => {
      keyStateRef.current = { left: false, right: false };
    };
    window.addEventListener('keydown', down);
    window.addEventListener('keyup', up);
    window.addEventListener('blur', blur);
    return () => {
      window.removeEventListener('keydown', down);
      window.removeEventListener('keyup', up);
      window.removeEventListener('blur', blur);
    };
  }, [openMenu, selectChoice]);

  // ──────────────────────────────────────────────────────────
  // Main rAF game loop (full-screen HiDPI canvas)
  // ──────────────────────────────────────────────────────────
  useEffect(() => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) return;
    let rafId = 0;
    let lastTime = performance.now();

    const loop = (time: number) => {
      rafId = requestAnimationFrame(loop);
      // Movement is scaled to 60 fps frames; long gaps (hidden tab) are clamped.
      const dt = Math.min(50, Math.max(0, time - lastTime));
      lastTime = time;
      const f = dt / FRAME_MS;

      const width = screenDimRef.current.w;
      const height = screenDimRef.current.h;
      const field = playfieldSize(width, height);
      const dpr = window.devicePixelRatio || 1;
      if (canvas.width !== Math.floor(width * dpr) || canvas.height !== Math.floor(height * dpr)) {
        canvas.width = Math.floor(width * dpr);
        canvas.height = Math.floor(height * dpr);
        canvas.style.width = '100%';
        canvas.style.height = '100%';
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

      const now = serverClock.now();
      tickClock(now);
      const currentPhase = phaseRef.current;
      const freezeState = stateRef.current;
      const freezeReference = freezeState?.status === 'PAUSED' && freezeState.pausedAt ? freezeState.pausedAt : now;
      soundManager.syncFreeze(
        currentPhase === 'playing' ? Math.max(0, freezeUntilRef.current - freezeReference) : 0,
        pausedRef.current || freezeState?.status !== 'RUNNING'
      );

      const pending = lockRef.current;
      if (pending && currentPhase === 'playing' && time - pending.at > LOCK_TIMEOUT_MS) {
        setLock(null);
        clearAnswerFish();
        activeRoundRef.current = null;
        showToast('Mạng hơi chậm, chờ một chút nhé…');
        void loadStateRef.current(true);
      }

      const shake = shakeRef.current;
      let sx = 0;
      let sy = 0;
      if (shake.duration > 0) {
        shake.duration--;
        sx = (Math.random() - 0.5) * shake.intensity * 2;
        sy = (Math.random() - 0.5) * shake.intensity * 2;
        shake.intensity *= 0.88;
      }

      ctx.save();
      ctx.translate(sx, sy);
      bgRef.current?.draw(ctx, width, height, time);

      const cat = catRef.current;
      cat.y = field.catY;

      if (currentPhase !== 'playing' && currentPhase !== 'waiting') {
        if (currentPhase === 'pregame' || currentPhase === 'loading') {
          cat.x = width / 2;
          mouseTargetXRef.current = width / 2;
          CatSpriteSheet.draw(ctx, cat, time);
        }
        ctx.restore();
        return;
      }

      const st = stateRef.current;
      const mine = meRef.current;
      const remaining = remainingMs(now);
      const running =
        currentPhase === 'playing' && !!st && !!mine && !mine.finished && st.status === 'RUNNING' && !pausedRef.current && remaining > 0;
      const frozen = now < freezeUntilRef.current;

      if (running) {
        // ── Cat movement
        let targetX = mouseTargetXRef.current;
        let lerp = 1 - Math.pow(1 - 0.16, f);
        if (keyStateRef.current.left || keyStateRef.current.right) {
          targetX = cat.x + (keyStateRef.current.left ? -cat.speed : cat.speed) * f;
          lerp = 1;
          mouseTargetXRef.current = targetX;
        }
        cat.x += (targetX - cat.x) * lerp;
        cat.x = Math.max(field.edge, Math.min(width - field.edge, cat.x));
        mouseTargetXRef.current = Math.max(field.edge, Math.min(width - field.edge, mouseTargetXRef.current));

        const showingFeedback = cat.state === 'catch_success' || cat.state === 'catch_wrong';
        if (!showingFeedback) {
          if (keyStateRef.current.left) cat.state = 'run_left';
          else if (keyStateRef.current.right) cat.state = 'run_right';
          else if (Math.abs(mouseTargetXRef.current - cat.x) > 12) {
            cat.state = mouseTargetXRef.current < cat.x ? 'run_left' : 'run_right';
          } else if (cat.state === 'run_left' || cat.state === 'run_right') {
            cat.state = 'idle';
          }
        }

        // ── Falling objects: report collisions, let the server decide
        const roundId = activeRoundRef.current?.id ?? null;
        const step = stepFallingObjects(fallingRef.current, {
          frames: f, frozen, basketX: cat.x, basketY: cat.y - field.basketOffset,
          catchRadius: field.catchRadius, missY: field.missY, roundId, locked: !!lockRef.current,
        });
        for (const item of step.bonuses) {
          sendActionRef.current('bonus', item.roundId, null, { x: item.x, y: item.y });
        }
        if (step.caught) {
          const item = step.caught;
          sendActionRef.current('catch', item.roundId, item.choiceId, { x: item.x, y: item.y });
          // One catch per round, right or wrong: the other answers leave at once, so nobody can
          // catch a wrong fish and then dash over to the right one (or the other way round).
          if (lockRef.current?.roundId === item.roundId) clearAnswerFish();
        }
        if (step.exhausted && roundId) {
          if (lockRef.current?.roundId === roundId) {
            missedWhileLockedRef.current = true;
          } else {
            sendActionRef.current('miss', roundId, null, { x: cat.x, y: field.missY });
          }
        }
        if (step.answersChanged) updatePresentChoices();
      }

      if (time - lastEffectUiRef.current >= 100) {
        lastEffectUiRef.current = time;
        const reference = st?.status === 'PAUSED' && st.pausedAt ? st.pausedAt : now;
        const nextFreeze = currentPhase === 'playing' ? Math.ceil(Math.max(0, freezeUntilRef.current - reference) / 100) * 100 : 0;
        const nextShield = currentPhase === 'playing' && cat.shieldActive;
        setEffectStatus(previous =>
          previous.freezeMs === nextFreeze && previous.shield === nextShield ? previous : { freezeMs: nextFreeze, shield: nextShield }
        );
      }

      for (const item of fallingRef.current) {
        SpriteRenderer.drawFallingItem(ctx, item);
      }
      CatSpriteSheet.draw(ctx, cat, time);
      particlesRef.current.updateAndDraw(ctx, field.scale);

      if (running && frozen) {
        ctx.save();
        const iceVignette = ctx.createRadialGradient(width / 2, height / 2, width * 0.3, width / 2, height / 2, width * 0.7);
        iceVignette.addColorStop(0, 'rgba(56, 189, 248, 0)');
        iceVignette.addColorStop(1, 'rgba(2, 132, 199, 0.18)');
        ctx.fillStyle = iceVignette;
        ctx.fillRect(0, 0, width, height);
        ctx.restore();
      }

      const texts = floatingTextsRef.current;
      for (let i = texts.length - 1; i >= 0; i--) {
        const ft = texts[i];
        ft.y += ft.vy * f * field.scale;
        ft.alpha -= 0.02 * f;
        if (ft.alpha <= 0) {
          texts.splice(i, 1);
          continue;
        }
        ctx.save();
        ctx.globalAlpha = Math.max(0, ft.alpha);
        ctx.font = `900 ${Math.max(12, ft.size * field.scale)}px ${DISPLAY_FONT_STACK}`;
        ctx.fillStyle = ft.color;
        ctx.textAlign = 'center';
        ctx.shadowColor = 'rgba(0,0,0,0.8)';
        ctx.shadowBlur = 8;
        ctx.fillText(ft.text, ft.x, ft.y);
        ctx.restore();
      }

      ctx.restore();
    };

    rafId = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(rafId);
  }, [clearAnswerFish, remainingMs, setLock, showToast, tickClock, updatePresentChoices]);

  // ──────────────────────────────────────────────────────────
  // Pointer / fullscreen
  // ──────────────────────────────────────────────────────────
  const handlePointer = (event: React.PointerEvent<HTMLCanvasElement>) => {
    if (phaseRef.current !== 'playing' || pausedRef.current) return;
    if (event.type === 'pointerdown' && event.pointerType === 'touch') {
      event.currentTarget.setPointerCapture(event.pointerId);
    }
    const rect = event.currentTarget.getBoundingClientRect();
    mouseTargetXRef.current = event.clientX - rect.left;
  };

  const toggleFullscreen = useCallback(() => {
    if (!document.fullscreenElement) {
      document.documentElement.requestFullscreen?.().then(() => setIsFullscreen(true)).catch(() => {});
    } else {
      document.exitFullscreen?.().then(() => setIsFullscreen(false)).catch(() => {});
    }
  }, []);

  const openHelp = useCallback(() => {
    helpOpenRef.current = true;
    setHelpOpen(true);
  }, []);

  const closeHelp = useCallback(() => {
    helpOpenRef.current = false;
    setHelpOpen(false);
  }, []);

  // ──────────────────────────────────────────────────────────
  // Render
  // ──────────────────────────────────────────────────────────
  const versus = (state?.mode ?? host.mode) === 'VERSUS';
  const config = state?.config ?? null;
  const meUserId = state?.meUserId ?? host.meId;
  const showHud = phase === 'playing' && !!state && !!me && !!config;
  const timeRatio = config ? Math.max(0, Math.min(1, timeLeft / Math.max(1, config.durationSeconds))) : 0;
  const timeIsLow = showHud && timeLeft <= 10;
  const questionFont = fieldFont(config?.questionField);
  const answerFont = fieldFont(config?.answerField);
  const questionLang = isJapaneseField(config?.questionField) ? 'ja' : 'vi';
  const answerLang = isJapaneseField(config?.answerField) ? 'ja' : 'vi';
  const choiceCount = activeRound?.choices.length ?? 0;
  const choiceColumns = choiceCount;
  const liveDown = liveStatus === 'offline' || (liveStatus === 'connecting' && liveWasOnlineRef.current);
  const backLabel = versus ? 'Về phòng' : 'Về trang Trò chơi';
  const returnUrl = gameBackUrl(state?.roomId || host.roomId, host.backUrl);

  return (
    <div className="neko-stage fixed inset-0 w-screen h-dvh overflow-hidden bg-[#0d0520] select-none" style={{ fontFamily: VI_FONT_STACK }}>
      <canvas
        ref={canvasRef}
        role="img"
        aria-label="Sân chơi Bắt cá: mèo hứng cá mang đáp án"
        className="absolute inset-0 w-full h-full block cursor-crosshair touch-none"
        onPointerMove={handlePointer}
        onPointerDown={handlePointer}
      />

      {/* ═══ IN-GAME HUD ═══ */}
      {showHud && me && config && (
        <>
          <div className="sr-only" role="region" aria-live="polite" aria-label="Thông tin màn chơi Bắt cá">
            {activeRound ? `Mục tiêu: ${activeRound.prompt}. ` : ''}
            {`Điểm ${me.score}, còn ${me.lives} tim.`}
            {effectStatus.freezeMs > 0 ? ' Đang đóng băng.' : ''}
            {effectStatus.shield ? ' Khiên đang sẵn sàng.' : ''}
          </div>

          <div className="neko-hud absolute top-3 left-3 right-3 sm:top-4 sm:left-5 sm:right-5 z-20 flex flex-wrap items-start justify-between gap-2 pointer-events-none">
            {/* LEFT: score, combo, opponent */}
            <div className="neko-hud__score-group flex flex-col gap-2 pointer-events-auto min-w-0">
              <div
                className="neko-hud__score flex items-center gap-2.5 px-3.5 sm:px-4 py-2 rounded-2xl"
                style={{ background: 'rgba(10,5,30,0.7)', backdropFilter: 'blur(12px)', border: '1px solid rgba(255,255,255,0.15)', boxShadow: '0 4px 20px rgba(0,0,0,0.4)' }}
              >
                <Trophy className="w-5 h-5 text-amber-400" />
                <span className="text-xs font-bold text-amber-300/80 tracking-wider">ĐIỂM</span>
                <span className="text-2xl font-black text-white ml-1 tabular-nums" style={{ fontFamily: DISPLAY_FONT_STACK }}>
                  {me.score.toString().padStart(5, '0')}
                </span>
              </div>

              {me.combo >= 2 && (
                <div
                  className="neko-hud__combo self-start flex items-center gap-1.5 px-3.5 py-1.5 rounded-2xl animate-bounce"
                  style={{ background: 'linear-gradient(135deg, #FF4757, #FF6B35)', boxShadow: '0 0 25px rgba(255,71,87,0.75)', border: '1px solid rgba(255,255,255,0.3)' }}
                >
                  <Sparkles className="w-4 h-4 text-yellow-200" />
                  <span className="text-base font-black text-white" style={{ fontFamily: DISPLAY_FONT_STACK }}>
                    COMBO ×{me.combo}
                  </span>
                </div>
              )}

              {versus && opponent && <div className="neko-hud__opponent"><OpponentPanel opponent={opponent} compact /></div>}
            </div>

            {/* CENTER: target word (second row on phones) */}
            {activeRound && (
              <div className="neko-hud__target-group order-last w-full sm:order-none sm:w-auto sm:flex-1 flex flex-col items-center gap-1.5 pointer-events-none">
                <div
                  className="neko-hud__target flex flex-col items-center px-5 sm:px-8 py-2.5 rounded-2xl pointer-events-auto max-w-[min(92vw,34rem)]"
                  style={{
                    background: 'linear-gradient(160deg, rgba(255,120,40,0.95), rgba(220,70,20,0.98))',
                    backdropFilter: 'blur(16px)',
                    border: '2px solid rgba(255,220,100,0.5)',
                    boxShadow: '0 0 40px rgba(255,100,40,0.6)',
                    minWidth: 'min(220px, 92vw)',
                  }}
                >
                  <span className="text-[10px] tracking-[0.22em] font-black text-amber-950 uppercase mb-0.5">MỤC TIÊU</span>
                  <span
                    lang={questionLang}
                    className={`neko-hud__prompt ${promptSizeClass(activeRound.prompt)} font-black text-white leading-tight text-center break-words max-w-full`}
                    style={{ fontFamily: questionFont, textShadow: '0 2px 14px rgba(0,0,0,0.5)' }}
                  >
                    {activeRound.prompt}
                  </span>
                  <span className="text-[10px] mt-1 px-3 py-0.5 rounded-full font-bold text-amber-200 bg-black/30 text-center">
                    {config.questionLabel} → {config.answerLabel}
                  </span>
                </div>
                {reveal && (
                  <div
                    key={reveal.id}
                    role="status"
                    className="pointer-events-auto max-w-[min(92vw,30rem)] px-4 py-1.5 rounded-xl text-xs font-bold text-emerald-50 bg-emerald-900/85 border border-emerald-300/50 shadow-lg backdrop-blur-md text-center animate-fade-in break-words"
                  >
                    Đáp án đúng:{' '}
                    <span className="text-sm font-black" lang={answerLang} style={{ fontFamily: answerFont }}>
                      {reveal.answer}
                    </span>
                  </div>
                )}
              </div>
            )}

            {/* RIGHT: hearts, buttons, timer, effects */}
            <div className="neko-hud__right flex flex-col items-end gap-2 pointer-events-auto">
              <div className="flex items-center gap-2">
                <div
                  className="flex items-center gap-1 sm:gap-1.5 px-3 sm:px-3.5 py-2 rounded-2xl"
                  style={{ background: 'rgba(10,5,30,0.7)', backdropFilter: 'blur(12px)', border: '1px solid rgba(255,255,255,0.15)' }}
                  role="img"
                  aria-label={`Còn ${me.lives} trên ${me.maxLives} tim`}
                >
                  {Array.from({ length: me.maxLives }).map((_, i) => (
                    <Heart key={i} className={`w-5 h-5 transition-all duration-300 ${i < me.lives ? 'text-red-400' : 'text-white/15'}`} />
                  ))}
                </div>

                <button
                  type="button"
                  onClick={toggleFullscreen}
                  aria-label={isFullscreen ? 'Thoát toàn màn hình' : 'Mở toàn màn hình'}
                  title={isFullscreen ? 'Thoát toàn màn hình' : 'Toàn màn hình'}
                  className="neko-hud__fullscreen hidden sm:flex w-11 h-11 rounded-2xl items-center justify-center bg-white/10 hover:bg-white/20 border border-white/20 text-amber-300 shadow-lg backdrop-blur-md transition-all hover:scale-105 active:scale-95 focus-visible:ring-2 focus-visible:ring-amber-400"
                >
                  <span aria-hidden="true">{isFullscreen ? <Minimize className="w-5 h-5" /> : <Maximize className="w-5 h-5" />}</span>
                </button>

                <button
                  type="button"
                  onClick={openMenu}
                  aria-label={versus ? 'Mở menu trận đấu (không tạm dừng)' : 'Tạm dừng và mở menu'}
                  title={versus ? 'Menu' : 'Tạm dừng'}
                  className="w-11 h-11 rounded-2xl flex items-center justify-center bg-amber-500/30 hover:bg-amber-500/50 border border-amber-400/50 text-white shadow-lg backdrop-blur-md transition-all hover:scale-105 active:scale-95 focus-visible:ring-2 focus-visible:ring-amber-400"
                >
                  <Settings className="w-5 h-5 text-amber-300" />
                </button>
              </div>

              <div className="flex flex-col items-end gap-1">
                <div
                  role="timer"
                  aria-label={`Còn ${timeLeft} giây`}
                  className="neko-hud__timer flex items-center gap-2 px-3.5 py-1.5 rounded-2xl"
                  style={{
                    background: timeIsLow ? 'rgba(220,38,38,0.85)' : 'rgba(10,5,30,0.7)',
                    backdropFilter: 'blur(12px)',
                    border: `1px solid ${timeIsLow ? 'rgba(255,100,100,0.6)' : 'rgba(255,255,255,0.15)'}`,
                    animation: timeIsLow && !pausedUi ? 'pulse 0.5s ease-in-out infinite' : 'none',
                  }}
                >
                  <Clock className={`w-4 h-4 ${timeIsLow ? 'text-red-200' : 'text-cyan-400'}`} />
                  <span className={`text-base font-black tabular-nums ${timeIsLow ? 'text-red-100' : 'text-white'}`} style={{ fontFamily: DISPLAY_FONT_STACK }}>
                    {timeLeft}s
                  </span>
                  {timeIsLow && <span className="hidden sm:inline text-[10px] font-black text-white uppercase tracking-wider">Sắp hết giờ</span>}
                </div>
                <div className="neko-hud__time-bar w-28 h-2 rounded-full overflow-hidden bg-white/15" aria-hidden="true">
                  <div
                    className="h-full rounded-full transition-all duration-500"
                    style={{
                      width: `${timeRatio * 100}%`,
                      background: timeIsLow ? 'linear-gradient(90deg, #FF4757, #FF6B35)' : 'linear-gradient(90deg, #2ED573, #1ABC9C)',
                    }}
                  />
                </div>
              </div>

              {(effectStatus.freezeMs > 0 || effectStatus.shield) && (
                <div className="neko-hud__effects flex flex-col items-end gap-1.5" role="status" aria-live="polite">
                  {effectStatus.freezeMs > 0 && (
                    <div className="w-40 overflow-hidden rounded-xl border border-sky-300/50 bg-sky-950/85 px-3 py-2 shadow-lg backdrop-blur-md">
                      <div className="flex items-center justify-between text-[11px] font-bold text-sky-100">
                        <span>Đóng băng</span>
                        <span className="tabular-nums">{(effectStatus.freezeMs / 1000).toFixed(1)}s</span>
                      </div>
                      <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-white/15">
                        <div
                          className="h-full rounded-full bg-sky-300 transition-[width] duration-100"
                          style={{ width: `${Math.min(100, effectStatus.freezeMs / 20)}%` }}
                        />
                      </div>
                    </div>
                  )}
                  {effectStatus.shield && (
                    <div className="w-40 rounded-xl border border-emerald-300/50 bg-emerald-950/85 px-3 py-2 text-[11px] font-bold text-emerald-100 shadow-lg backdrop-blur-md">
                      Khiên · sẵn sàng chặn lỗi
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>

          {/* BOTTOM: accessible keyboard / touch choices */}
          {!mobile && activeRound && choiceCount > 0 && (
            <div
              className="absolute bottom-3 sm:bottom-4 left-1/2 -translate-x-1/2 z-20 pointer-events-auto grid gap-1.5 sm:gap-2 bg-slate-950/85 backdrop-blur-md p-1.5 sm:p-2 rounded-2xl border border-amber-400/40 shadow-2xl w-[min(96vw,980px)]"
              style={{ gridTemplateColumns: `repeat(${Math.max(1, choiceColumns)}, minmax(0, 1fr))` }}
              role="group"
              aria-label={`Chọn đáp án bằng phím 1–${Math.min(9, choiceCount)} hoặc nhấn trực tiếp`}
            >
              {activeRound.choices.map((choice, i) => {
                const present = presentChoices.includes(choice.id);
                return (
                  <button
                    key={choice.id}
                    type="button"
                    onClick={() => selectChoice(i)}
                    disabled={!present}
                    aria-disabled={!present || roundLocked}
                    aria-label={`Chọn đáp án số ${i + 1}: ${choice.text}`}
                    title={choice.text}
                    className="min-w-0 min-h-[44px] sm:min-h-[52px] px-2 sm:px-3 py-1.5 rounded-xl font-bold text-white bg-white/10 hover:bg-amber-500/30 border border-white/20 hover:border-amber-400 transition-all flex items-center gap-2 shadow focus-visible:ring-2 focus-visible:ring-amber-400 disabled:opacity-35 disabled:hover:bg-white/10 disabled:hover:border-white/20"
                  >
                    {i < 9 && (
                      <span className="w-5 h-5 rounded-md bg-amber-400 text-slate-950 font-black flex items-center justify-center text-[11px] shrink-0">
                        {i + 1}
                      </span>
                    )}
                    <span
                      lang={answerLang}
                      className="min-w-0 text-xs sm:text-sm leading-tight line-clamp-2 break-words text-left"
                      style={{ fontFamily: answerFont }}
                    >
                      {choice.text}
                    </span>
                  </button>
                );
              })}
            </div>
          )}
        </>
      )}

      {/* ═══ STATUS PILLS ═══ */}
      {(toast || (liveDown && (phase === 'playing' || phase === 'waiting')) || (audioLocked && phase === 'playing')) && (
        <div className="neko-status absolute left-1/2 -translate-x-1/2 bottom-32 sm:bottom-28 z-40 flex flex-col items-center gap-2 pointer-events-none w-[min(92vw,26rem)]">
          {toast && (
            <p role={toast.type === 'success' ? 'status' : 'alert'}
              className={`pointer-events-auto w-full text-center text-xs font-semibold border rounded-xl px-3 py-2 shadow-lg ${toast.type === 'success'
                ? 'text-emerald-50 bg-emerald-900/90 border-emerald-400/50'
                : 'text-rose-50 bg-rose-900/90 border-rose-400/50'}`}>
              {toast.message}
            </p>
          )}
          {liveDown && (phase === 'playing' || phase === 'waiting') && (
            <p role="status" className="text-center text-[11px] font-bold text-slate-100 bg-slate-800/90 border border-slate-500/50 rounded-full px-3 py-1 shadow">
              Mất kết nối realtime — đang kết nối lại…
            </p>
          )}
          {audioLocked && phase === 'playing' && (
            <button
              type="button"
              onClick={() => soundManager.unlock()}
              className="pointer-events-auto inline-flex items-center gap-1.5 text-[11px] font-bold text-amber-100 bg-slate-900/90 border border-amber-400/50 rounded-full px-3 py-1.5 min-h-[36px] shadow focus-visible:ring-2 focus-visible:ring-amber-400"
            >
              <Volume2 className="w-4 h-4" />
              Chạm để bật âm thanh
            </button>
          )}
        </div>
      )}

      {/* ═══ "BẮT ĐẦU!" FLASH WHEN THE MATCH STARTS ═══ */}
      {goFlash && (
        <div className="absolute inset-0 z-40 flex items-center justify-center pointer-events-none" aria-hidden="true">
          <div
            className="text-[64px] sm:text-[110px] leading-none font-black text-amber-300 drop-shadow-[0_0_60px_rgba(255,159,67,0.95)] tracking-widest animate-fade-in"
            style={{ fontFamily: DISPLAY_FONT_STACK }}
          >
            BẮT ĐẦU!
          </div>
        </div>
      )}

      {/* ═══ 3-2-1 COUNTDOWN ON RESUME (SOLO) ═══ */}
      {resumeCountdown !== null && (
        <div className="absolute inset-0 z-40 flex flex-col items-center justify-center bg-black/60 backdrop-blur-md animate-fade-in pointer-events-auto" role="timer" aria-live="assertive">
          <div className="flex flex-col items-center gap-3 animate-bounce">
            <div
              className="text-[88px] sm:text-[120px] leading-none font-black text-amber-300 drop-shadow-[0_0_60px_rgba(255,159,67,0.95)] tracking-widest text-center"
              style={{ fontFamily: DISPLAY_FONT_STACK }}
            >
              {resumeCountdown}
            </div>
            <p className="text-base font-extrabold text-amber-100 tracking-widest uppercase bg-amber-500/30 px-6 py-1.5 rounded-full border border-amber-400/40 shadow-xl">
              SẴN SÀNG HỨNG CÁ
            </p>
          </div>
        </div>
      )}

      {/* ═══ LOADING ═══ */}
      {phase === 'loading' && (
        <div className="absolute inset-0 z-30 flex items-center justify-center p-4 bg-black/55 backdrop-blur-sm">
          <div role="status" className="flex flex-col items-center gap-3 px-8 py-6 rounded-3xl text-center" style={panelStyle}>
            {!assetError && <Spinner />}
            <p className="text-sm font-bold text-white/85">{assetError ?? (state ? 'Đang tải hình trò chơi…' : 'Đang tải trận đấu…')}</p>
            {assetError && <button type="button" onClick={() => setAssetRetry(value => value + 1)} className="min-h-[44px] px-4 rounded-lg bg-white/10 font-bold text-white">Thử tải lại</button>}
          </div>
        </div>
      )}

      {/* ═══ ERROR ═══ */}
      {phase === 'error' && (
        <div className="absolute inset-0 z-30 flex items-center justify-center p-4 bg-black/65 backdrop-blur-md">
          <div role="alert" className="w-full max-w-sm flex flex-col items-center gap-3 p-6 rounded-3xl text-center" style={panelStyle}>
            <h2 className="text-xl font-black text-white uppercase tracking-widest" style={{ fontFamily: DISPLAY_FONT_STACK }}>
              Không vào được trận
            </h2>
            <p className="text-sm text-white/75 break-words">{fatalError}</p>
            <div className="w-full flex gap-2 mt-1">
              {sessionId && hasArena() && (
                <button
                  type="button"
                  onClick={retryLoad}
                  className="flex-1 py-3 min-h-[44px] rounded-2xl font-black text-white text-xs uppercase tracking-widest focus-visible:ring-2 focus-visible:ring-amber-300"
                  style={{ fontFamily: DISPLAY_FONT_STACK, background: 'linear-gradient(135deg, #FF9F43, #FF6B35)' }}
                >
                  Thử lại
                </button>
              )}
              <a
                href={returnUrl}
                className="flex-1 py-3 min-h-[44px] rounded-2xl font-bold text-white/85 text-xs uppercase tracking-widest bg-white/10 border border-white/15 hover:bg-white/20 flex items-center justify-center no-underline focus-visible:ring-2 focus-visible:ring-amber-300"
                style={{ fontFamily: DISPLAY_FONT_STACK }}
              >
                {backLabel}
              </a>
            </div>
          </div>
        </div>
      )}

      {/* ═══ PRE-GAME CARD + COUNTDOWN TO startAt ═══ */}
      {/* Luyện tập đã bấm "Bắt đầu": đếm ngược 3-2-1 lớn giữa màn hình. */}
      {phase === 'pregame' && state && !state.awaitingStart && state.mode !== 'VERSUS' && pregameCountdown !== null && (
        <div className="absolute inset-0 z-40 flex flex-col items-center justify-center bg-black/60 backdrop-blur-md animate-fade-in pointer-events-auto" role="timer" aria-live="assertive">
          <div className="flex flex-col items-center gap-3 animate-bounce">
            <div
              className="text-[88px] sm:text-[120px] leading-none font-black text-amber-300 drop-shadow-[0_0_60px_rgba(255,159,67,0.95)] tracking-widest text-center"
              style={{ fontFamily: DISPLAY_FONT_STACK }}
            >
              {pregameCountdown}
            </div>
            <p className="text-base font-extrabold text-amber-100 tracking-widest uppercase bg-amber-500/30 px-6 py-1.5 rounded-full border border-amber-400/40 shadow-xl">
              SẴN SÀNG HỨNG CÁ
            </p>
          </div>
        </div>
      )}

      {phase === 'pregame' && state && (state.awaitingStart || state.mode === 'VERSUS') && (
        <PregameCard
          state={state}
          me={me}
          opponent={opponent}
          countdown={pregameCountdown}
          awaitingStart={!!state.awaitingStart}
          deckCards={deckCards}
          skinOptions={skinOptions}
          skinBalance={skinShop?.balance ?? null}
          skinBusy={skinBusy}
          skinError={skinError}
          onSkinRetry={reloadSkinShop}
          onSkinChange={changeSkin}
          starting={starting}
          assetsReady={assetsReady}
          assetError={assetError}
          onAssetRetry={() => setAssetRetry(value => value + 1)}
          onStart={startMatch}
          skin={skin}
          onBack={() => { void leaveMatch().catch(() => { window.location.href = returnUrl; }); }}
          isFullscreen={isFullscreen}
          onToggleFullscreen={toggleFullscreen}
        />
      )}

      {/* ═══ FINISHED, WAITING FOR THE OPPONENT / THE SUMMARY ═══ */}
      {phase === 'waiting' && me && (
        <div className="absolute inset-0 z-30 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm">
          <div role="status" aria-live="polite" className="w-full max-w-sm flex flex-col items-center gap-3 p-6 rounded-3xl text-center animate-fade-in" style={panelStyle}>
            {me.finishReason && (
              <p className="text-[10px] font-black uppercase tracking-[0.2em] text-amber-300/80">{finishReasonLabel(me.finishReason)}</p>
            )}
            <h2 className="text-2xl font-black text-white uppercase tracking-widest" style={{ fontFamily: DISPLAY_FONT_STACK }}>
              {versus ? 'Đang chờ đối thủ…' : 'Đang tổng kết…'}
            </h2>
            <p className="text-sm text-white/80">
              Điểm của bạn: <strong className="text-amber-300 tabular-nums">{me.score}</strong> · {me.correct} đúng · {me.wrong} sai
            </p>
            {versus && opponent && (
              <div className="w-full flex justify-center">
                <OpponentPanel opponent={opponent} />
              </div>
            )}
            <Spinner />
            {versus && (
              <button
                type="button"
                onClick={openMenu}
                className="mt-1 px-4 py-2.5 min-h-[44px] rounded-2xl text-xs font-bold text-white/80 bg-white/10 border border-white/15 hover:bg-white/20 focus-visible:ring-2 focus-visible:ring-amber-400"
              >
                Menu
              </button>
            )}
          </div>
        </div>
      )}

      {/* ═══ RESULT ═══ */}
      {phase === 'result' && summary && state && (
        <ResultScreen
          summary={summary}
          mode={state.mode}
          meId={meUserId}
          me={me}
          opponent={opponent}
          mistakes={mistakes}
          deckTitle={state.deckTitle}
          questionField={state.config.questionField}
          answerField={state.config.answerField}
          skin={skin}
          backUrl={returnUrl}
          onReplay={playAgain}
          replaying={replaying}
          replayError={replayError}
        />
      )}

      {/* ═══ ABORTED ═══ */}
      {phase === 'aborted' && (
        <div className="absolute inset-0 z-30 flex items-center justify-center p-4 bg-black/70 backdrop-blur-md">
          <div role="alert" className="w-full max-w-sm flex flex-col items-center gap-3 p-6 rounded-3xl text-center" style={panelStyle}>
            <h2 className="text-xl font-black text-white uppercase tracking-widest" style={{ fontFamily: DISPLAY_FONT_STACK }}>
              Trận đấu đã bị huỷ
            </h2>
            <p className="text-sm text-white/75">Trận kết thúc mà không có kết quả.</p>
            <a
              href={versus ? returnUrl : '/games'}
              className="w-full py-3 min-h-[44px] rounded-2xl font-black text-white text-xs uppercase tracking-widest flex items-center justify-center no-underline focus-visible:ring-2 focus-visible:ring-amber-300"
              style={{ fontFamily: DISPLAY_FONT_STACK, background: 'linear-gradient(135deg, #FF9F43, #FF6B35)' }}
            >
              {backLabel}
            </a>
          </div>
        </div>
      )}

      {/* ═══ SETTINGS / PAUSE MENU ═══ */}
      <MenuModal
        open={menuOpen}
        mode={state?.mode ?? host.mode}
        paused={!versus && pausedUi}
        finished={!!me?.finished}
        onClose={closeMenu}
        onHelp={openHelp}
        onLeave={leaveMatch}
        suspended={helpOpen}
      />

      <HelpModal
        open={helpOpen}
        onClose={closeHelp}
        questionLabel={config?.questionLabel ?? 'từ hỏi'}
        answerLabel={config?.answerLabel ?? 'đáp án'}
        choiceCount={config?.choices ?? 4}
        versus={versus}
        mobile={mobile}
      />
    </div>
  );
};
