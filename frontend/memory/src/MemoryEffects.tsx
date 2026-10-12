import { motion } from 'framer-motion';
import { useEffect, useRef, useState } from 'react';
import { playCountdownSfx } from './sfx';
import type { DeckField } from './cardTypes';
export interface DicePlayer { userId:number; displayName:string; avatar?:string; pairsFound:number; mistakes:number; moves:number; streak:number; connected:boolean; }
export interface MemoryResultView {
  playMode:'SOLO'|'MULTIPLAYER'; status:'RUNNING'|'PAUSED'|'FINISHED'|'ABORTED'; outcome?:'CLEARED'|'TIME_UP'|'ABANDONED';
  config:{questionField:DeckField;answerField:DeckField}; players:DicePlayer[]; pairsMatched:number;pairsTotal:number;movesUsed:number;elapsedMs:number;
  result?:{success:boolean;draw:boolean;winnerUserId?:number;durationMs:number;strugglingTerms?:{question:string;answer:string;reading?:string;meaning?:string;hanViet?:string}[]};
}
function isJapaneseField(field?:DeckField) { return field === 'KANJI' || field === 'HIRAGANA'; }
function initials(name:string) { return name.trim().split(/\s+/).slice(-2).map(part=>part[0]).join('').toUpperCase(); }
function formatDuration(ms:number) { const seconds=Math.max(0,Math.floor(ms/1000)); return `${Math.floor(seconds/60)}:${String(seconds%60).padStart(2,'0')}`; }
export function PlayerPanel({ player, isMe, side, active }: {
  player?: DicePlayer
  isMe?: boolean
  side: 'sakura' | 'kaito'
  active: boolean
}) {
  const name = player?.displayName ?? 'Đang chờ người chơi'
  const score = player ? player.pairsFound * 100 : 0
  return (
    <aside className={`memory-player-panel memory-player-panel--${side} ${active ? 'is-active' : ''} ${player ? '' : 'is-empty'}`}>
      <div className="memory-player-panel__turn">{active ? (isMe ? 'LƯỢT CỦA BẠN' : 'ĐANG TỚI LƯỢT') : 'ĐANG CHỜ LƯỢT'}</div>
      <div className="memory-player-panel__avatar">
        {player?.avatar ? <img src={player.avatar} alt="" /> : <span>{player ? initials(name) : '?'}</span>}
      </div>
      <h2>{name}</h2>
      <p className="memory-player-panel__status"><i />{player && !player.connected ? 'Mất kết nối' : active ? 'Đang tới lượt' : 'Đang chờ lượt'}</p>
      <div className="memory-player-panel__score"><small>ĐIỂM</small><strong>{score}</strong></div>
      {player && <div className="memory-player-panel__pairs">Cặp đã tìm <strong>{player.pairsFound}</strong></div>}
    </aside>
  )
}

function DiceFace({ value, rolling }: { value: number; rolling: boolean }) {
  return (
    <motion.div
      className={`memory-dice-face ${rolling ? 'is-rolling' : ''}`}
      animate={rolling ? { rotate: [0, -10, 10, -8, 8, 0], scale: [1, 1.08, 1] } : { rotate: 0, scale: 1 }}
      transition={rolling ? { duration: 0.55, repeat: Infinity } : { duration: 0.22 }}
      aria-label={`Xúc xắc ${value}`}
    >
      {Array.from({ length: value }, (_, index) => <i key={index} />)}
    </motion.div>
  )
}

export function DiceRollOverlay({
  players,
  starterIndex,
  onStart,
  onCountdown = playCountdownSfx
}: {
  players: DicePlayer[]
  starterIndex: number
  onStart: () => void
  onCountdown?: (step: number) => void
}) {
  const [rolling, setRolling] = useState(true)
  const [countdown, setCountdown] = useState(3)
  const [values, setValues] = useState<[number, number]>([4, 2])
  const starter = players[starterIndex]

  useEffect(() => {
    let step = 3
    onCountdown(step)
    const timer = window.setInterval(() => {
      step -= 1
      if (step > 0) {
        setCountdown(step)
        onCountdown(step)
        return
      }
      window.clearInterval(timer)
      setCountdown(0)
      setValues(starterIndex === 0 ? [4, 2] : [2, 4])
      setRolling(false)
      onCountdown(1)
    }, 700)
    return () => window.clearInterval(timer)
  }, [starterIndex, onCountdown])

  // App vẽ lại mỗi 250 ms nên onStart luôn là hàm mới; giữ nó trong ref để bộ đếm 5,2 giây không bị đặt lại mãi.
  const onStartRef = useRef(onStart)
  onStartRef.current = onStart
  useEffect(() => {
    const timer = window.setTimeout(() => onStartRef.current(), 5200)
    return () => window.clearTimeout(timer)
  }, [])

  return (
    <motion.div className="memory-dice-overlay" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} role="dialog" aria-labelledby="memory-dice-title">
      <motion.section className="memory-dice-dialog" initial={{ y: 22, scale: .96 }} animate={{ y: 0, scale: 1 }}>
        <p className="memory-dice-kicker">VÒNG ĐẤU BẮT ĐẦU</p>
        <h2 id="memory-dice-title">Ai đi trước?</h2>
        <p className="memory-dice-subtitle">Xem ai được lật thẻ trước</p>
        <div className="memory-dice-countdown" aria-live="assertive">{countdown || 'GO!'}</div>
        <div className="memory-dice-players">
          {players.slice(0, 2).map((player, index) => (
            <div className={`memory-dice-player ${index === starterIndex && !rolling ? 'is-winner' : ''}`} key={player.userId}>
              <div className="memory-dice-avatar">{player.avatar ? <img src={player.avatar} alt="" /> : initials(player.displayName)}</div>
              <strong>{player.displayName}</strong>
              <DiceFace value={values[index]} rolling={rolling} />
              <small>{rolling ? 'Đang tung…' : index === starterIndex ? 'Đi trước' : 'Đi sau'}</small>
            </div>
          ))}
        </div>
        <motion.p className="memory-dice-result" animate={{ opacity: rolling ? 0 : 1, y: rolling ? 5 : 0 }}>
          {starter?.displayName} đi trước!
        </motion.p>
        <button className="memory-btn memory-btn--primary memory-dice-start" type="button" onClick={onStart} disabled={rolling}>
          {rolling ? 'ĐANG TUNG XÚC XẮC…' : 'BẮT ĐẦU'}
        </button>
      </motion.section>
    </motion.div>
  )
}

export function ResultPanel({ state, userId, backUrl, onReplay, replaying }: {
  state: MemoryResultView
  userId: number | null
  backUrl: string
  onReplay: () => void
  replaying: boolean
}) {
  const result = state.result
  const versus = state.playMode === 'MULTIPLAYER'
  const title = state.status === 'ABORTED'
    ? 'Ván đã bị huỷ'
    : versus
      ? result?.draw ? 'Hoà!' : result?.winnerUserId === userId ? 'Bạn thắng!' : 'Đối thủ thắng'
      : result?.success ? 'Hoàn thành!' : 'Ván chơi kết thúc'
  const subtitle = state.status === 'ABORTED'
    ? 'Phòng đã đóng giữa ván nên kết quả không được tính.'
    : state.outcome === 'ABANDONED'
      ? 'Một người chơi đã rời bàn giữa ván.'
      : state.outcome === 'TIME_UP'
        ? 'Hết thời gian, những cặp đã ghép vẫn được tính.'
        : versus
          ? 'Ai ghép nhiều cặp hơn thắng; bằng cặp thì ai ít lần sai hơn thắng.'
          : 'Bạn đã ghép hết các cặp trên bàn.'
  const ranked = [...state.players].sort((a, b) => b.pairsFound - a.pairsFound || a.mistakes - b.mistakes)

  return (
    <motion.section
      className="memory-result"
      initial={{ opacity: 0, y: 24 }}
      animate={{ opacity: 1, y: 0 }}
      role="dialog"
      aria-labelledby="memory-result-title"
    >
      <div className="memory-result__seal" aria-hidden="true">勝</div>
      <p className="memory-kicker">Kết quả Lật thẻ</p>
      <h2 id="memory-result-title">{title}</h2>
      <p>{subtitle}</p>
      {versus ? (
        <ol className="memory-result__ranking" aria-label="Bảng xếp hạng ván đấu">
          {ranked.map((player) => (
            <li key={player.userId} className={result?.winnerUserId === player.userId ? 'is-winner' : ''}>
              <strong>{player.displayName}{player.userId === userId ? ' (bạn)' : ''}</strong>
              <span>{player.pairsFound} cặp · {player.mistakes} lần sai · {player.moves} lượt</span>
            </li>
          ))}
        </ol>
      ) : (
        <div className="memory-result__stats">
          <span><strong>{state.pairsMatched}/{state.pairsTotal}</strong>Cặp đúng</span>
          <span><strong>{state.movesUsed}</strong>Lượt lật</span>
          <span><strong>{formatDuration(result?.durationMs ?? state.elapsedMs)}</strong>Thời gian</span>
        </div>
      )}
      {!!result?.strugglingTerms?.length && (
        <div className="memory-review">
          <h3>Từ nên ôn lại</h3>
          <div className="memory-review__list">
            {result.strugglingTerms.map((term) => (
              <div key={`${term.question}-${term.answer}`}>
                <strong className={isJapaneseField(state.config.questionField) ? 'jp' : ''}>{term.question}</strong>
                <span>
                  {[term.answer, term.reading, term.meaning, term.hanViet]
                    .filter((value, index, list): value is string => !!value && value !== term.question && list.indexOf(value) === index)
                    .join(' · ')}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
      <div className="memory-result__actions">
        {!versus && (
          <button className="memory-btn memory-btn--primary" onClick={onReplay} disabled={replaying}>
            {replaying ? 'Đang tạo ván…' : 'Chơi lại'}
          </button>
        )}
        <a className="memory-btn" href={backUrl}>
          {versus ? 'Về phòng' : 'Về trang Trò chơi'}
        </a>
      </div>
    </motion.section>
  )
}
