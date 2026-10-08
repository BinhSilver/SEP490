import { PlayerPanel, DiceRollOverlay, ResultPanel } from './MemoryEffects';
import { MemoryCardButton } from './MemoryCardButton'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useEffect, useMemo, useRef, useState } from 'react'
import type {
  DeckCardView,
  DeckField,
  MemoryEnvelope,
  MemoryPlayer,
  MemoryStartOptions,
  MemoryState,
  MemoryStoreLike
} from './types'
import { MemorySetup } from './MemorySetup'
import { useMemoryUiStore } from './session-store'
import { playClickSfx, playCompleteSfx, playCountdownSfx, playFlipSfx, playMatchSfx, playMismatchSfx, setMemoryVolume, startBackgroundMusic, stopBackgroundMusic, unlockMemoryAudio } from './sfx'

const EVENT_LABELS: Record<string, string> = {
  MEMORY_TURN_TIMEOUT: 'Hết thời gian của lượt này.',
  MEMORY_GAME_OVER: 'Ván chơi đã kết thúc.'
}

const FIELD_LABELS: Record<DeckField, string> = {
  KANJI: 'Kanji',
  HIRAGANA: 'Cách đọc',
  MEANING: 'Nghĩa',
  HAN_VIET: 'Hán Việt'
}

function isJapaneseField(field?: DeckField): boolean {
  return field === 'KANJI' || field === 'HIRAGANA'
}

function secondsLeft(deadline: number | undefined, serverNow: number): number | null {
  if (!deadline) return null
  return Math.max(0, Math.ceil((deadline - serverNow) / 1000))
}

function formatDuration(milliseconds: number): string {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

function initials(name: string): string {
  return name.trim().split(/\s+/).slice(-2).map((part) => part[0]).join('').toUpperCase()
}

function PlayerChip({ player, isMe }: { player: MemoryPlayer; isMe: boolean }) {
  return (
    <article className={`memory-player ${player.currentTurn ? 'is-current' : ''}`}>
      <div className="memory-player__avatar" aria-hidden="true">
        {player.avatar ? <img src={player.avatar} alt="" /> : initials(player.displayName)}
      </div>
      <div className="memory-player__main">
        <div className="memory-player__name">
          <strong className="truncate">{player.displayName}</strong>
          {isMe && <span className="memory-mini-tag">Bạn</span>}
        </div>
        <span>{player.currentTurn ? 'Đang tới lượt' : `${player.pairsFound} cặp · chuỗi ${player.streak}`}</span>
      </div>
      <strong className="memory-player__score">{player.pairsFound * 100}</strong>
    </article>
  )
}

/**
 * Cỡ chữ vừa khít mặt thẻ: thử 1–4 dòng, chọn số dòng cho chữ to nhất mà vẫn nằm gọn theo cả bề ngang (--fit-w)
 * lẫn bề cao (--fit-h). Đơn vị cqw/cqh theo khung thẻ (container-type: size) nên đúng ở mọi cỡ màn hình.
 */
export default function App({ sessionId, backUrl }: { sessionId: string; backUrl: string }) {
  const reduceMotion = useReducedMotion()
  const storeRef = useRef<MemoryStoreLike | null>(null)
  const feedbackTimerRef = useRef<number | null>(null)
  const streakRef = useRef(0)
  const previousStatusRef = useRef<string | null>(null)
  const state = useMemoryUiStore((ui) => ui.snapshot)
  const connection = useMemoryUiStore((ui) => ui.connection)
  const error = useMemoryUiStore((ui) => ui.error)
  const notice = useMemoryUiStore((ui) => ui.notice)
  const setState = useMemoryUiStore((ui) => ui.setSnapshot)
  const setConnection = useMemoryUiStore((ui) => ui.setConnection)
  const setError = useMemoryUiStore((ui) => ui.setError)
  const setNotice = useMemoryUiStore((ui) => ui.setNotice)
  const resetUi = useMemoryUiStore((ui) => ui.reset)
  const [userId, setUserId] = useState<number | null>(null)
  const [now, setNow] = useState(Date.now())
  const [pendingPause, setPendingPause] = useState(false)
  const [replaying, setReplaying] = useState(false)
  const [roundFeedback, setRoundFeedback] = useState<'match' | 'mismatch' | null>(null)
  const [feedbackCardIds, setFeedbackCardIds] = useState<string[]>([])
  const [soundEnabled, setSoundEnabled] = useState(() => window.localStorage.getItem('memory-sfx') !== 'off')
  const [volume, setVolume] = useState(() => {
    const saved = Number(window.localStorage.getItem('memory-volume'))
    return Number.isFinite(saved) && saved >= 0 && saved <= 1 ? saved : 1
  })
  const [volumeOpen, setVolumeOpen] = useState(false)
  const [showDiceRoll, setShowDiceRoll] = useState(false)
  const [deckCards, setDeckCards] = useState<DeckCardView[] | null>(null)
  const [starting, setStarting] = useState(false)
  const [setupError, setSetupError] = useState<string | null>(null)
  const countdownBeepRef = useRef<number | null>(null)
  const diceSessionRef = useRef<string | null>(null)
  const soundEnabledRef = useRef(soundEnabled)

  useEffect(() => {
    resetUi()
    const store = new window.MemoryStore(sessionId)
    storeRef.current = store
    const offChange = store.on('change', setState)
    const offConnection = store.on('connection', setConnection)
    const offEvent = store.on('event', (event: MemoryEnvelope) => {
      if (event.type === 'MEMORY_ERROR') {
        const message = event.payload && 'message' in event.payload
          && typeof event.payload.message === 'string' ? event.payload.message : undefined
        setError(message || 'Không thể thực hiện nước đi này')
        return
      }
      const message = EVENT_LABELS[event.type]
      if (event.type === 'MEMORY_PAIR_MATCHED' || event.type === 'MEMORY_PAIR_MISMATCH') {
        if (soundEnabledRef.current) {
          if (event.type === 'MEMORY_PAIR_MATCHED') playMatchSfx(Math.max(1, streakRef.current + 1))
          else playMismatchSfx()
        }
        setRoundFeedback(event.type === 'MEMORY_PAIR_MATCHED' ? 'match' : 'mismatch')
        if (feedbackTimerRef.current != null) window.clearTimeout(feedbackTimerRef.current)
        feedbackTimerRef.current = window.setTimeout(() => {
          setRoundFeedback(null)
          setFeedbackCardIds([])
          feedbackTimerRef.current = null
        }, event.type === 'MEMORY_PAIR_MATCHED' ? 900 : 720)
      }
      if (message) {
        setNotice(message)
        window.setTimeout(() => {
          if (useMemoryUiStore.getState().notice === message) setNotice(null)
        }, 1800)
      }
    })

    Promise.all([
      window.JlasArena.me(),
      window.JlasArena.api.get<MemoryState>(`/api/games/memory/${encodeURIComponent(sessionId)}`)
    ])
      .then(([me, initialState]) => {
        store.currentUserId = me.userId
        setUserId(me.userId)
        store.applyState(initialState)
        store.connect()
      })
      .catch((cause: Error) => setError(cause.message || 'Không thể tải ván chơi'))

    return () => {
      offChange()
      offConnection()
      offEvent()
      if (feedbackTimerRef.current != null) window.clearTimeout(feedbackTimerRef.current)
      store.destroy()
      storeRef.current = null
    }
  }, [resetUi, sessionId, setConnection, setError, setNotice, setState])

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 250)
    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => {
    soundEnabledRef.current = soundEnabled
    window.localStorage.setItem('memory-sfx', soundEnabled ? 'on' : 'off')
    if (!soundEnabled) stopBackgroundMusic()
  }, [soundEnabled])

  useEffect(() => {
    setMemoryVolume(volume)
    window.localStorage.setItem('memory-volume', String(volume))
  }, [volume])

  useEffect(() => {
    if (!soundEnabled) return
    const startMusic = () => startBackgroundMusic()
    window.addEventListener('pointerdown', startMusic, { once: true })
    window.addEventListener('keydown', startMusic, { once: true })
    return () => {
      window.removeEventListener('pointerdown', startMusic)
      window.removeEventListener('keydown', startMusic)
      stopBackgroundMusic()
    }
  }, [soundEnabled])

  // Màn cài đặt trước ván: tải thẻ của bộ để chọn cặp hỏi – đáp và thẻ muốn chơi.
  const awaitingStart = Boolean(state?.awaitingStart)
  const deckId = state?.deckId
  useEffect(() => {
    if (!awaitingStart || !deckId || deckCards) return
    let cancelled = false
    window.JlasArena.api.get<DeckCardView[]>(`/api/games/decks/${deckId}/cards`)
      .then((cards) => { if (!cancelled) setDeckCards(cards) })
      .catch(() => { if (!cancelled) setDeckCards([]) })
    return () => { cancelled = true }
  }, [awaitingStart, deckId, deckCards])

  useEffect(() => {
    const currentPlayer = state?.players.find((player) => player.userId === userId)
    streakRef.current = currentPlayer?.streak ?? 0
  }, [state?.players, userId])

  useEffect(() => {
    if (!state || state.playMode !== 'MULTIPLAYER' || state.status !== 'RUNNING' || state.players.length < 2) return
    if (diceSessionRef.current === state.sessionId) return
    diceSessionRef.current = state.sessionId
    setShowDiceRoll(true)
  }, [state?.sessionId, state?.playMode, state?.status, state?.players.length])

  useEffect(() => {
    const previous = previousStatusRef.current
    if (soundEnabled && previous === 'RUNNING' && state?.status === 'FINISHED') playCompleteSfx()
    previousStatusRef.current = state?.status ?? null
  }, [soundEnabled, state?.status])

  const serverNow = storeRef.current?.serverNow() ?? now
  const turnLeft = secondsLeft(state?.turnDeadlineAt, serverNow)
  const totalLeft = secondsLeft(state?.totalDeadlineAt, serverNow)
  const me = state?.players.find((player) => player.userId === userId)
  const myTurn = state?.playMode === 'SOLO' || state?.currentTurnUserId === userId
  // Bàn luyện tập có thể là số ô bất kỳ (12–40): chọn số cột ít ô trống nhất, hoà thì lấy mức quen thuộc 4/5/6.
  const boardColumns = useMemo(() => {
    const count = state?.cards.length ?? 20
    const preferred = count <= 12 ? 4 : count <= 20 ? 5 : 6
    const candidates = count <= 12 ? [4, 3] : count <= 20 ? [5, 4, 6] : [6, 5, 7]
    const empty = (cols: number) => Math.ceil(count / cols) * cols - count
    return candidates.reduce((best, cols) => (empty(cols) < empty(best) ? cols : best), preferred)
  }, [state?.cards.length])
  // Luyện tập: sau khi bấm "Bắt đầu", đếm ngược 3-2-1 tới lúc bắt đầu tính giờ (turnStartedAt).
  const soloCountdown = state && state.playMode === 'SOLO' && !state.awaitingStart && state.turnStartedAt
    && state.movesUsed === 0 && serverNow < state.turnStartedAt
    ? Math.ceil((state.turnStartedAt - serverNow) / 1000) : null
  useEffect(() => {
    if (soloCountdown == null || countdownBeepRef.current === soloCountdown) return
    countdownBeepRef.current = soloCountdown
    if (soundEnabled) playCountdownSfx(soloCountdown)
  }, [soloCountdown, soundEnabled])
  const canFlip = Boolean(state && !showDiceRoll && soloCountdown == null && state.status === 'RUNNING' && !state.resolving && myTurn && connection === 'online')

  const flip = (cardId: string) => {
    setError(null)
    if (storeRef.current?.flip(cardId)) {
      if (soundEnabled) playFlipSfx()
      setFeedbackCardIds((current) => current.includes(cardId) ? current : [...current, cardId].slice(-2))
    } else {
      setError('Đang mất kết nối, chưa thể lật thẻ')
    }
  }

  const togglePause = async () => {
    if (!state || state.playMode !== 'SOLO' || pendingPause) return
    setPendingPause(true)
    setError(null)
    try {
      const action = state.status === 'PAUSED' ? 'resume' : 'pause'
      const next = await window.JlasArena.api.post<MemoryState>(
        `/api/games/memory/${encodeURIComponent(sessionId)}/${action}`
      )
      storeRef.current?.applyState(next)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Không thể đổi trạng thái ván')
    } finally {
      setPendingPause(false)
    }
  }

  const replay = async () => {
    if (!state || replaying) return
    setReplaying(true)
    setError(null)
    try {
      const next = await window.JlasArena.api.solo({
        gameType: 'MEMORY_MATCH',
        settings: {
          setId: state.deckId,
          questionField: state.config.questionField,
          answerField: state.config.answerField,
          options: {
            boardSize: state.config.boardSize,
            objective: state.config.objective,
            turnSeconds: state.config.turnSeconds,
            totalSeconds: state.config.totalSeconds,
            keepTurnOnMatch: state.config.keepTurnOnMatch
          }
        }
      })
      window.location.href = next.playUrl
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Không thể tạo ván mới')
      setReplaying(false)
    }
  }

  const startGame = async (options: MemoryStartOptions) => {
    if (starting) return
    setStarting(true)
    setSetupError(null)
    unlockMemoryAudio()
    try {
      const next = await window.JlasArena.api.post<MemoryState>(
        `/api/games/memory/${encodeURIComponent(sessionId)}/start`, options)
      storeRef.current?.applyState(next)
    } catch (cause) {
      setSetupError(cause instanceof Error ? cause.message : 'Không bắt đầu được, thử lại.')
    } finally {
      setStarting(false)
    }
  }

  /** Luyện tập, trước khi bấm "Bắt đầu": huỷ ván đang chờ rồi quay về trang trước. */
  const backFromSetup = async () => {
    try {
      await window.JlasArena.api.leaveSession(sessionId)
    } catch {
      // Ván chưa bắt đầu: có lỗi cũng cứ quay về, server tự dọn ván bỏ không.
    }
    window.location.href = backUrl
  }

  /** Đối kháng: rời bàn giữa ván là xử thua, nên hỏi lại trước khi rời. */
  const leaveBoard = async (event: React.MouseEvent<HTMLAnchorElement>) => {
    if (!state || state.playMode !== 'MULTIPLAYER' || state.status === 'FINISHED' || state.status === 'ABORTED') return
    event.preventDefault()
    if (!window.confirm('Rời bàn bây giờ sẽ bị xử thua ván này. Bạn chắc chắn muốn rời?')) return
    try {
      await window.JlasArena.api.leaveSession(sessionId)
    } catch {
      // Dù gọi lỗi vẫn đưa người chơi về phòng; server sẽ tự kết thúc ván bị bỏ.
    }
    window.location.href = backUrl
  }

  if (!state) {
    return (
      <main className="memory-loading" aria-live="polite">
        <div className="memory-loader" aria-hidden="true"><span>記</span></div>
        <h1>Đang xếp thẻ…</h1>
        <p>{error || 'Chuẩn bị bàn lật thẻ của bạn'}</p>
        {error && <a className="memory-btn" href={backUrl}>Quay lại</a>}
      </main>
    )
  }

  if (state.awaitingStart && state.playMode === 'SOLO' && state.status === 'RUNNING') {
    return (
      <MemorySetup state={state} deckCards={deckCards} starting={starting} error={setupError}
        onStart={(options) => { void startGame(options) }} onBack={() => { void backFromSetup() }} />
    )
  }

  if (state.status === 'FINISHED' || state.status === 'ABORTED') {
    return (
      <main className="memory-shell memory-shell--result">
        <ResultPanel state={state} userId={userId} backUrl={backUrl} onReplay={replay} replaying={replaying} />
      </main>
    )
  }

  return (
    <main className={`memory-shell ${roundFeedback ? `is-${roundFeedback}` : ''}`}>
      <div className={`memory-layout ${state.playMode === 'SOLO' ? 'is-solo' : ''}`}>
        {/* Chơi một mình: không cần bảng người chơi, bàn thẻ nằm giữa. */}
        {state.playMode === 'MULTIPLAYER' && (
          <PlayerPanel player={state.players[0]} isMe={state.players[0]?.userId === userId} side="sakura" active={Boolean(state.players[0]?.currentTurn)} />
        )}
        <section className="memory-play-area">
          <div className="memory-board-wrap">
            <AnimatePresence>
              {roundFeedback && (
                <motion.div
                  className={`memory-round-feedback is-${roundFeedback}`}
                  initial={{ opacity: 0, scale: 0.65 }}
                  animate={{ opacity: 1, scale: 1 }}
                  exit={{ opacity: 0, scale: 1.18 }}
                  transition={{ duration: reduceMotion ? 0 : 0.22 }}
                  aria-hidden="true"
                >
                  <span>{roundFeedback === 'match' ? '✓' : '×'}</span>
                  <span className="memory-round-feedback__copy">
                    <strong>{roundFeedback === 'match' ? ((me?.streak ?? 0) >= 2 ? `COMBO ×${me?.streak}` : 'ĐÚNG RỒI!') : 'CHƯA ĐÚNG'}</strong>
                    {roundFeedback === 'match' && (me?.streak ?? 0) >= 2 && <em>TUYỆT!</em>}
                  </span>
                  {roundFeedback === 'match' && <><i /><i /><i /><i /><i /><i /></>}
                </motion.div>
              )}
            </AnimatePresence>
            <div className="memory-turn memory-turn--sample" aria-live="polite">
              <span className={myTurn ? 'is-mine' : ''}>{state.status === 'PAUSED' ? 'VÁN ĐANG TẠM DỪNG' : myTurn ? 'CHỌN HAI THẺ' : 'ĐỐI THỦ ĐANG CHỌN THẺ…'}</span>
              <small>{state.deckTitle} · {FIELD_LABELS[state.config.questionField]} ↔ {FIELD_LABELS[state.config.answerField]}</small>
            </div>
            <div className="memory-board" style={{ '--board-cols': boardColumns } as React.CSSProperties}>
              {state.cards.slice().sort((a, b) => a.position - b.position).map((card) => (
                <MemoryCardButton
                  key={card.cardInstanceId}
                  card={card}
                  disabled={!canFlip}
                  onFlip={flip}
                  reduceMotion={reduceMotion}
                  feedback={roundFeedback && feedbackCardIds.includes(card.cardInstanceId) ? roundFeedback : null}
                />
              ))}
            </div>
          </div>
        </section>

        {state.playMode === 'MULTIPLAYER' && (
          <PlayerPanel player={state.players[1]} isMe={state.players[1]?.userId === userId} side="kaito" active={Boolean(state.players[1]?.currentTurn)} />
        )}
        <div className="memory-play-toolbar">
          <div className="memory-head-metrics memory-head-metrics--sidebar" aria-label="Thông số ván chơi">
            {state.playMode === 'SOLO' && <>
              <span><small>Cặp</small><strong>{state.pairsMatched}/{state.pairsTotal}</strong></span>
              <i />
              <span><small>Điểm</small><strong>{(me?.pairsFound ?? 0) * 100}</strong></span>
              <i />
            </>}
            <span><small>Lượt</small><strong>{state.movesUsed}</strong></span>
            <i />
            <span><small>Thời gian</small><strong className={turnLeft != null && turnLeft <= 5 ? 'is-urgent' : ''}>{totalLeft != null ? formatDuration(totalLeft * 1000) : formatDuration((turnLeft ?? 0) * 1000)}</strong></span>
            <i />
            <span><small>Combo</small><strong className={(me?.streak ?? 0) >= 2 ? 'is-combo' : ''}>x{me?.streak ?? 0}</strong></span>
          </div>
          <div className="memory-sidebar-controls">
            <span className={`memory-connection is-${connection}`}><i />{connection === 'online' ? 'Đã kết nối' : connection === 'connecting' ? 'Đang kết nối' : 'Mất kết nối'}</span>
            <div className="memory-sidebar-controls__buttons">
          <div className="memory-volume-control">
            <button
              type="button"
              className="memory-icon-btn memory-icon-btn--sound"
              onClick={() => {
                unlockMemoryAudio()
                playClickSfx()
                if (soundEnabled) stopBackgroundMusic()
                else startBackgroundMusic()
                setSoundEnabled((enabled) => !enabled)
              }}
              aria-label={soundEnabled ? 'Tắt âm thanh' : 'Bật âm thanh'}
              title={soundEnabled ? 'Tắt âm thanh' : 'Bật âm thanh'}
            >
              {soundEnabled ? '🔊' : '🔇'}
              <span className="memory-icon-btn__label">{soundEnabled ? 'Tắt tiếng' : 'Bật tiếng'}</span>
            </button>
            <button
              type="button"
              className="memory-volume-control__toggle"
              onClick={() => { if (soundEnabled) playClickSfx(); setVolumeOpen((open) => !open) }}
              aria-label="Tùy chỉnh âm lượng"
              aria-expanded={volumeOpen}
              title="Tùy chỉnh âm lượng"
            >
              ⚙
            </button>
            <AnimatePresence>
              {volumeOpen && (
                <motion.div
                  className="memory-volume-popover"
                  initial={{ opacity: 0, y: -6, scale: .96 }}
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  exit={{ opacity: 0, y: -6, scale: .96 }}
                  transition={{ duration: reduceMotion ? 0 : .16 }}
                >
                  <div><strong>Âm lượng</strong><output>{Math.round(volume * 100)}%</output></div>
                  <input
                    type="range"
                    min="0"
                    max="100"
                    value={Math.round(volume * 100)}
                    onChange={(event) => setVolume(Number(event.target.value) / 100)}
                    aria-label="Âm lượng trò chơi"
                  />
                </motion.div>
              )}
            </AnimatePresence>
          </div>
          {state.playMode === 'SOLO' && (
            <button type="button" className="memory-icon-btn" onClick={() => { if (soundEnabled) playClickSfx(); void togglePause() }} disabled={pendingPause} aria-label={state.status === 'PAUSED' ? 'Tiếp tục' : 'Tạm dừng'}>
              {state.status === 'PAUSED' ? '▶' : 'Ⅱ'}
              <span className="memory-icon-btn__label">{state.status === 'PAUSED' ? 'Tiếp tục' : 'Tạm dừng'}</span>
            </button>
          )}
          <a className="memory-icon-btn memory-icon-btn--exit" href={backUrl} onClick={leaveBoard} aria-label="Rời bàn" title="Rời bàn">↪<span className="memory-icon-btn__label">Rời bàn</span></a>
            </div>
          </div>
        </div>
      </div>

      <AnimatePresence>
        {notice && <motion.div className="memory-notice" initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 12 }}>{notice}</motion.div>}
      </AnimatePresence>
      {error && <div className="memory-error" role="alert"><span>{error}</span><button onClick={() => setError(null)} aria-label="Đóng">×</button></div>}
      {(connection === 'offline' || state.status === 'PAUSED') && (
        <div className="memory-overlay" role="status">
          <div><span className="memory-overlay__glyph">{state.status === 'PAUSED' ? '休' : '結'}</span><h2>{state.status === 'PAUSED' ? 'Đã tạm dừng' : 'Đang kết nối lại'}</h2><p>{state.status === 'PAUSED' ? 'Đồng hồ đã dừng. Tiếp tục khi bạn sẵn sàng.' : 'Chờ kết nối lại rồi lật thẻ tiếp nhé.'}</p>{state.status === 'PAUSED' && <button className="memory-btn memory-btn--primary" onClick={togglePause}>Tiếp tục</button>}</div>
        </div>
      )}
      {soloCountdown != null && (
        <div className="memory-countdown" role="timer" aria-live="assertive">
          <strong key={soloCountdown}>{soloCountdown}</strong>
          <span>Sẵn sàng lật thẻ</span>
        </div>
      )}
      <AnimatePresence>
        {showDiceRoll && state.playMode === 'MULTIPLAYER' && state.players.length >= 2 && (
          <DiceRollOverlay
            players={state.players}
            starterIndex={state.players.findIndex((player) => player.userId === state.currentTurnUserId) === 1 ? 1 : 0}
            onStart={() => setShowDiceRoll(false)}
          />
        )}
      </AnimatePresence>
    </main>
  )
}
