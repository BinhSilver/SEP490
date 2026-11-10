import { motion } from 'framer-motion';
import type { DeckField } from './cardTypes';
export interface MemoryEffectPlayer { userId:number; displayName:string; avatar?:string; pairsFound:number; mistakes:number; moves:number; streak:number; connected:boolean; }
export interface MemoryResultView {
  playMode:'SOLO'|'MULTIPLAYER'; status:'RUNNING'|'PAUSED'|'FINISHED'|'ABORTED'; outcome?:'CLEARED'|'TIME_UP'|'ABANDONED';
  config:{questionField:DeckField;answerField:DeckField}; players:MemoryEffectPlayer[]; pairsMatched:number;pairsTotal:number;movesUsed:number;elapsedMs:number;
  result?:{success:boolean;draw:boolean;winnerUserId?:number;durationMs:number;strugglingTerms?:{question:string;answer:string;reading?:string;meaning?:string;hanViet?:string}[]};
}
function isJapaneseField(field?:DeckField) { return field === 'KANJI' || field === 'HIRAGANA'; }
function initials(name:string) { return name.trim().split(/\s+/).slice(-2).map(part=>part[0]).join('').toUpperCase(); }
function formatDuration(ms:number) { const seconds=Math.max(0,Math.floor(ms/1000)); return `${Math.floor(seconds/60)}:${String(seconds%60).padStart(2,'0')}`; }
export function PlayerPanel({ player, isMe, side, active }: {
  player?: MemoryEffectPlayer
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
  const won = versus ? !result?.draw && result?.winnerUserId === userId : !!result?.success
  // The seal is the one bold element: a hanko stamped onto the panel, its character naming the outcome.
  const seal = state.status === 'ABORTED' ? '止' : versus ? (result?.draw ? '引' : won ? '勝' : '負') : (won ? '完' : '終')
  const tone = state.status === 'ABORTED' ? 'muted' : won ? 'won' : result?.draw ? 'draw' : 'lost'
  const totalPairs = ranked.reduce((sum, player) => sum + player.pairsFound, 0)
  const terms = result?.strugglingTerms ?? []

  return (
    <motion.section
      className={`memory-final memory-final--${tone}`}
      initial={{ opacity: 0, y: 24 }}
      animate={{ opacity: 1, y: 0 }}
      role="dialog"
      aria-labelledby="memory-result-title"
    >
      <motion.div
        className="memory-final__seal"
        aria-hidden="true"
        initial={{ scale: 1.6, rotate: -18, opacity: 0 }}
        animate={{ scale: 1, rotate: -6, opacity: 1 }}
        transition={{ delay: 0.25, type: 'spring', stiffness: 420, damping: 18 }}
      >{seal}</motion.div>
      <h2 id="memory-result-title">{title}</h2>
      <p className="memory-final__lead">{subtitle}</p>

      {versus ? (
        <>
          <ol className="memory-final__players" aria-label="Kết quả từng người chơi">
            {ranked.map((player) => {
              const winner = !result?.draw && result?.winnerUserId === player.userId
              return (
                <li key={player.userId} className={winner ? 'is-winner' : ''}>
                  {winner && <span className="memory-final__badge">Thắng</span>}
                  <span className="memory-final__name">
                    {player.displayName}{player.userId === userId && <small> (bạn)</small>}
                  </span>
                  <span className="memory-final__pairs"><strong>{player.pairsFound}</strong> cặp</span>
                  <span className="memory-final__facts">
                    <span><b>{player.mistakes}</b> lần sai</span>
                    <span><b>{player.moves}</b> lượt</span>
                  </span>
                </li>
              )
            })}
          </ol>
          {totalPairs > 0 && ranked.length === 2 && (
            <div className="memory-final__race" role="img"
              aria-label={`Số cặp: ${ranked[0].displayName} ${ranked[0].pairsFound}, ${ranked[1].displayName} ${ranked[1].pairsFound}`}>
              <i style={{ flexGrow: ranked[0].pairsFound }} />
              <i style={{ flexGrow: ranked[1].pairsFound }} />
            </div>
          )}
        </>
      ) : (
        <dl className="memory-final__stats">
          <div><dt>Cặp đúng</dt><dd>{state.pairsMatched}<small>/{state.pairsTotal}</small></dd></div>
          <div><dt>Lượt lật</dt><dd>{state.movesUsed}</dd></div>
          <div><dt>Thời gian</dt><dd>{formatDuration(result?.durationMs ?? state.elapsedMs)}</dd></div>
        </dl>
      )}

      {terms.length > 0 && (
        <section className="memory-final__review" aria-labelledby="memory-review-title">
          <h3 id="memory-review-title">Từ nên ôn lại <span>{terms.length} từ</span></h3>
          <ul>
            {terms.map((term) => {
              const extra = [term.reading, term.meaning, term.hanViet]
                .filter((value, index, list): value is string => !!value && value !== term.question
                  && value !== term.answer && list.indexOf(value) === index)
              return (
                <li key={`${term.question}-${term.answer}`}>
                  <strong className={isJapaneseField(state.config.questionField) ? 'jp' : ''}>{term.question}</strong>
                  <span>{term.answer}</span>
                  {extra.length > 0 && <small>{extra.join(', ')}</small>}
                </li>
              )
            })}
          </ul>
        </section>
      )}
      <div className="memory-final__actions">
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
