import { useEffect, useRef, useState } from 'react'
import { useReducedMotion } from 'framer-motion'
import type { CoinSide, CoinTossState, MemoryPlayer } from './types'
import './coin-toss.css'

const MASCOT = '/images/mascots/kitsu/kitsu-coin-smile.png'
const LAND_AT = 3600
export function coinMoment(coin: CoinTossState, now: number) {
  const elapsed = coin.startedAt == null ? 0 : Math.max(0, now - coin.startedAt)
  return { elapsed, landed: coin.startedAt != null && elapsed >= LAND_AT,
    complete: coin.playStartsAt != null && now >= coin.playStartsAt }
}
export function CoinFace({ side, imageSrc = MASCOT }: { side: CoinSide; imageSrc?: string }) {
  return side === 'JLAS' ? <strong className="coin-face__brand">JLAS</strong>
    : <img className="coin-face__mascot" src={imageSrc} alt="Mascot đang cười" />
}

/** Two real faces; its flight seeks the shared server clock when reconnecting. */
export function TossingCoin({ result, elapsed, reduced = false, imageSrc = MASCOT }: {
  result: CoinSide; elapsed: number; reduced?: boolean; imageSrc?: string
}) {
  const angle = result === 'JLAS' ? 2160 : 2340
  const offset = useRef(elapsed).current
  return <div className={`coin-stage${reduced ? ' coin-stage--reduced' : ''}`} aria-hidden="true">
    <div className="coin-shadow" style={{ animationDelay: `${-offset}ms` }} />
    <div className="coin-flight" style={{ animationDelay: `${-offset}ms` }}>
      <div className="coin-body" style={{ '--coin-end': `${angle}deg`, animationDelay: `${-offset}ms` } as React.CSSProperties}>
        <div className="coin-face coin-face--front"><CoinFace side="JLAS" /></div>
        <div className="coin-face coin-face--back"><CoinFace side="MASCOT" imageSrc={imageSrc} /></div>
      </div>
    </div>
  </div>
}

export function CoinTossOverlay({ coin, players, meId, serverNow, onChoose, onDone, onLeave }: {
  coin: CoinTossState
  players: MemoryPlayer[]
  meId: number | null
  serverNow: number
  onChoose: (side: CoinSide) => Promise<void>
  onDone: () => void
  onLeave: () => void
}) {
  const reduced = !!useReducedMotion()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [assetReady, setAssetReady] = useState(false)
  const [assetFailed, setAssetFailed] = useState(false)
  const [retry, setRetry] = useState(0)
  const inFlight = useRef(false)
  const dialog = useRef<HTMLElement>(null)
  const done = useRef(onDone)
  done.current = onDone
  const imageSrc = MASCOT + (retry ? `?retry=${retry}` : '')
  useEffect(() => {
    const img = new Image()
    img.onload = () => { setAssetReady(true); setAssetFailed(false) }
    img.onerror = () => setAssetFailed(true)
    img.src = imageSrc
    return () => { img.onload = null; img.onerror = null }
  }, [retry])
  const started = coin.startedAt != null
  const { elapsed, landed, complete } = coinMoment(coin, serverNow)
  useEffect(() => {
    const root = dialog.current
    if (!root) return
    const previous = document.activeElement as HTMLElement | null
    const controls = () => Array.from(root.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'))
    ;(controls()[0] || root).focus()
    const trap = (event: KeyboardEvent) => {
      if (event.key !== 'Tab') return
      const buttons = controls(), first = buttons[0], last = buttons.at(-1)
      if (!first) { event.preventDefault(); return }
      if (event.shiftKey && (document.activeElement === first || document.activeElement === root)) { event.preventDefault(); last?.focus() }
      else if (!event.shiftKey && (document.activeElement === last || document.activeElement === root)) { event.preventDefault(); first.focus() }
    }
    root.addEventListener('keydown', trap)
    return () => { root.removeEventListener('keydown', trap); if (previous?.isConnected) previous.focus() }
  }, [assetReady, started])
  useEffect(() => {
    if (complete) done.current()
  }, [complete])
  const choose = async (side: CoinSide) => {
    if (inFlight.current || started || !assetReady) return
    inFlight.current = true; setBusy(true); setError(null)
    try { await onChoose(side) } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Chưa chọn được mặt đồng xu. Thử lại nhé.')
    } finally { inFlight.current = false; setBusy(false) }
  }
  const starter = players.find(player => player.userId === coin.starterUserId)
  return <div className="coin-overlay" role="dialog" aria-modal="true" aria-labelledby="coin-title">
    <section className="coin-dialog" ref={dialog} tabIndex={-1}>
      <h2 id="coin-title">{started ? 'Ai đi trước?' : 'Chọn mặt đồng xu'}</h2>
      {started && coin.result ? <TossingCoin result={coin.result} elapsed={elapsed} reduced={reduced} imageSrc={imageSrc} />
        : <div className="coin-choices" role="group" aria-label="Mặt đồng xu của bạn">
          {(['JLAS', 'MASCOT'] as CoinSide[]).map(side => {
            const owner = players.find(player => coin.choices[player.userId] === side)
            const mine = owner?.userId === meId
            return <button type="button" key={side} className={`coin-choice${mine ? ' is-selected' : ''}`}
              aria-pressed={mine} disabled={busy || !assetReady || meId == null || !!(owner && !mine)}
              onClick={() => void choose(side)}>
              <span className="coin-face coin-choice__face"><CoinFace side={side} imageSrc={imageSrc} /></span>
              <strong>{side === 'JLAS' ? 'JLAS' : 'Mascot'}</strong>
              <small>{owner ? (mine ? 'Bạn đã chọn' : owner.displayName) : 'Chọn mặt này'}</small>
            </button>
          })}
        </div>}
      <p className="coin-status" role="status" aria-live="polite">
        {landed ? `${coin.result === 'JLAS' ? 'JLAS' : 'Mascot'} · ${starter?.displayName || 'Người chơi'} đi trước!`
          : started ? 'Đang tung đồng xu…'
            : busy ? 'Đang chọn…' : meId != null && coin.choices[meId] ? 'Chờ đối thủ chọn mặt còn lại…'
              : assetReady ? 'Chọn một mặt để bắt đầu' : 'Đang tải đồng xu…'}
      </p>
      {(error || assetFailed) && <p className="coin-error" role="alert">{error || 'Chưa tải được hình mascot.'}</p>}
      {assetFailed && <button className="memory-btn" type="button" onClick={() => { setAssetFailed(false); setRetry(n => n + 1) }}>Tải lại hình</button>}
      {!started && <button className="coin-leave" type="button" onClick={onLeave}>Về phòng</button>}
    </section>
  </div>
}
