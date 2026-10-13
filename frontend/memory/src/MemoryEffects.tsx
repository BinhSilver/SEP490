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
