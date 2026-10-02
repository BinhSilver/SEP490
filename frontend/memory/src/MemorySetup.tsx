import { useEffect, useRef, useState } from 'react'
import type { DeckCardView, DeckField, MemoryStartOptions, MemoryState } from './types'

/** Giống GameRules.MEMORY_MIN_PAIRS / MEMORY_MAX_PAIRS / MEMORY_TOTAL_SECONDS của server. */
const MIN_PAIRS = 6
const MAX_PAIRS = 20
const TIME_LIMITS: (number | null)[] = [null, 180, 300, 600]
/** Ước tính ~15 giây ghép được một cặp. */
const SECONDS_PER_PAIR = 15

function formatTime(seconds: number | null): string {
  return seconds == null ? 'Không giới hạn' : `${seconds / 60} phút`
}

/** Mức thời gian gần nhất với số cặp × 15 giây. */
function recommendTime(pairs: number): number {
  const target = pairs * SECONDS_PER_PAIR
  return (TIME_LIMITS.filter((s): s is number => s != null))
    .reduce((best, s) => (Math.abs(s - target) < Math.abs(best - target) ? s : best))
}

/** Ô chọn thả xuống (tự vẽ, không dùng <select> mặc định); mức gợi ý có nhãn "Gợi ý". */
function Dropdown<T extends string | number | null>({ label, value, options, format, onChange, recommended }: {
  label: string
  value: T
  options: T[]
  format: (option: T) => string
  onChange: (option: T) => void
  recommended?: T
}) {
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const rootRef = useRef<HTMLDivElement>(null)
  const listId = `memory-dd-${label.replace(/\s+/g, '-')}`

  useEffect(() => {
    if (!open) return
    const onDown = (event: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [open])

  const tag = (option: T) => recommended !== undefined && option === recommended
    ? <span className="memory-setup__tag">Gợi ý</span> : null
  const choose = (option: T) => {
    onChange(option)
    setOpen(false)
  }
  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape') { setOpen(false); return }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      const step = event.key === 'ArrowDown' ? 1 : -1
      if (!open) { setOpen(true); setActive(Math.max(0, options.indexOf(value))); return }
      setActive((index) => (index + step + options.length) % options.length)
      return
    }
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      if (open) choose(options[active])
      else { setOpen(true); setActive(Math.max(0, options.indexOf(value))) }
    }
  }

  return (
    <div className="memory-setup__group">
      <span className="memory-setup__label" id={`${listId}-label`}>{label}</span>
      <div className="memory-dd" ref={rootRef}>
        <button type="button" className="memory-dd__trigger" aria-haspopup="listbox" aria-expanded={open}
          aria-labelledby={`${listId}-label`} aria-controls={listId} onKeyDown={onKeyDown}
          onClick={() => { setOpen((v) => !v); setActive(Math.max(0, options.indexOf(value))) }}>
          <span>{format(value)}{tag(value)}</span>
          <i className="fa-solid fa-chevron-down memory-dd__caret" aria-hidden="true" />
        </button>
        {open && (
          <ul className="memory-dd__list" role="listbox" id={listId} aria-labelledby={`${listId}-label`}>
            {options.map((option, index) => (
              <li key={String(option)} role="option" aria-selected={option === value}
                className={`${option === value ? 'is-selected' : ''} ${index === active ? 'is-active' : ''}`}
                onMouseEnter={() => setActive(index)} onMouseDown={(event) => { event.preventDefault(); choose(option) }}>
                {format(option)}{tag(option)}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

/** Cảnh báo khi bấm Bắt đầu; kèm cài đặt nên đổi sang (nếu có). */
type PaceWarning = { message: string; fix: number | null; fixLabel: string | null }
const FIELDS: DeckField[] = ['KANJI', 'HIRAGANA', 'MEANING', 'HAN_VIET']
const FIELD_LABELS: Record<DeckField, string> = {
  KANJI: 'Kanji',
  HIRAGANA: 'Cách đọc',
  MEANING: 'Nghĩa',
  HAN_VIET: 'Hán Việt'
}

function fieldValue(card: DeckCardView, field: DeckField): string | null {
  switch (field) {
    case 'KANJI': return card.kanji
    case 'HIRAGANA': return card.reading
    case 'MEANING': return card.meaning
    default: return card.hanViet
  }
}

function OptionGroup<T extends string | number | null>({ label, value, options, format, onChange, disabledReason, recommended, stretch = false }: {
  label: string
  value: T
  options: T[]
  format: (option: T) => string
  onChange: (option: T) => void
  disabledReason?: (option: T) => string | null
  recommended?: T
  /** Các nút chia đều hết bề ngang, không để khoảng trống. */
  stretch?: boolean
}) {
  return (
    <div className="memory-setup__group">
      <span className="memory-setup__label">{label}</span>
      <div className={`memory-setup__options${stretch ? ' memory-setup__options--stretch' : ''}`} role="radiogroup" aria-label={label}>
        {options.map((option) => {
          const selected = option === value
          const reason = disabledReason ? disabledReason(option) : null
          return (
            <button
              key={String(option)}
              type="button"
              role="radio"
              aria-checked={selected}
              disabled={!!reason && !selected}
              title={reason ?? undefined}
              className={selected ? 'is-selected' : ''}
              onClick={() => onChange(option)}
            >
              {format(option)}
              {recommended !== undefined && option === recommended && <span className="memory-setup__tag">Gợi ý</span>}
            </button>
          )
        })}
      </div>
    </div>
  )
}

/**
 * Cài đặt trước ván luyện tập: cặp hỏi – đáp (chỉ trường mà mọi thẻ đang chọn đều có), thẻ muốn chơi, số thẻ trên bàn,
 * giới hạn thời gian. Bấm "Bắt đầu" thì server dựng lại bàn và đếm ngược 3 giây.
 */
export function MemorySetup({ state, deckCards, starting, error, onStart, onBack }: {
  state: MemoryState
  deckCards: DeckCardView[] | null
  starting: boolean
  error: string | null
  onStart: (options: MemoryStartOptions) => void
  onBack: () => void
}) {
  const { config } = state
  const [question, setQuestion] = useState<DeckField>(config.questionField)
  const [answer, setAnswer] = useState<DeckField>(config.answerField)
  const [timeLimit, setTimeLimit] = useState<number | null>(config.objective === 'TIME_ATTACK' ? config.totalSeconds ?? 300 : null)
  const [warning, setWarning] = useState<PaceWarning | null>(null)
  const [selected, setSelected] = useState<Set<number> | null>(null)
  const cards = deckCards ?? []
  const chosen = selected ?? new Set(cards
    .filter((card) => fieldValue(card, config.questionField) && fieldValue(card, config.answerField))
    .map((card) => card.id))
  const chosenCards = cards.filter((card) => chosen.has(card.id))
  const usable = (card: DeckCardView) => !!fieldValue(card, question) && !!fieldValue(card, answer)

  const missingCount = (field: DeckField) => chosenCards.filter((card) => !fieldValue(card, field)).length
  const fieldReason = (field: DeckField, other: DeckField) => {
    if (field === other) return 'Hỏi và đáp phải khác nhau'
    const missing = missingCount(field)
    return missing ? `${missing} thẻ đang chọn chưa có ${FIELD_LABELS[field]}` : null
  }

  // Mỗi cặp trên bàn là một thẻ: bàn N thẻ cần ít nhất N/2 thẻ khác nhau (bỏ trùng câu hỏi / đáp án như server).
  const distinct = new Set(chosenCards.filter(usable).map((card) => `${fieldValue(card, question)}`.toLowerCase())).size
  // Bàn dựng theo đúng số thẻ đã chọn (mỗi thẻ một cặp = 2 ô), trong khoảng 6–20 cặp như server.
  const pairs = Math.min(distinct, MAX_PAIRS)
  const board = pairs * 2
  const suggestedTime = recommendTime(Math.max(pairs, MIN_PAIRS))

  const paceWarning = (seconds: number | null): PaceWarning | null => {
    if (seconds != null && seconds / SECONDS_PER_PAIR < pairs / 2) {
      const better = recommendTime(pairs)
      return {
        message: `Với ${board} thẻ trên bàn, ${formatTime(seconds)} chỉ đủ ghép khoảng ${Math.round(seconds / SECONDS_PER_PAIR)}/${pairs} cặp.`,
        fix: better > seconds ? better : null, fixLabel: better > seconds ? `Đổi sang ${formatTime(better)}` : null
      }
    }
    return null
  }

  const start = (seconds: number | null, confirmed = false) => {
    const warn = confirmed ? null : paceWarning(seconds)
    setWarning(warn)
    if (warn) return
    onStart({
      questionField: question,
      answerField: answer,
      cardIds: chosenCards.filter(usable).map((card) => card.id),
      boardSize: null,
      objective: seconds == null ? 'CLASSIC' : 'TIME_ATTACK',
      totalSeconds: seconds
    })
  }

  const applyFix = () => {
    if (!warning?.fix) return
    setTimeLimit(warning.fix)
    start(warning.fix)
  }

  const toggleCard = (id: number) => {
    const next = new Set(chosen)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    setSelected(next)
  }

  const problem = deckCards === null ? 'Đang tải thẻ của bộ…'
    : distinct < MIN_PAIRS ? `Chọn ít nhất ${MIN_PAIRS} thẻ để chơi (đang chọn ${distinct}).`
      : null

  return (
    <main className="memory-setup">
      <section className="memory-setup__panel" aria-labelledby="memory-setup-title">
        <header className="memory-setup__head">
          <button type="button" className="memory-setup__back" onClick={onBack}>
            <i className="fa-solid fa-chevron-left" aria-hidden="true" /> Quay về
          </button>
          <div>
            <p className="memory-kicker">Luyện tập một mình</p>
            <h1 id="memory-setup-title">Lật thẻ</h1>
            <p className="memory-setup__deck">{state.deckTitle}</p>
          </div>
        </header>

        <div className="memory-setup__row memory-setup__row--2">
          <OptionGroup<DeckField> label="Hỏi bằng" value={question} options={FIELDS} format={(field) => FIELD_LABELS[field]}
            disabledReason={(field) => fieldReason(field, answer)} onChange={setQuestion} />
          <OptionGroup<DeckField> label="Đáp bằng" value={answer} options={FIELDS} format={(field) => FIELD_LABELS[field]}
            disabledReason={(field) => fieldReason(field, question)} onChange={setAnswer} />
        </div>

        <div className="memory-setup__group">
          <div className="memory-setup__cards-head">
            <span className="memory-setup__label">Thẻ · {chosenCards.length}/{cards.length} đã chọn</span>
            <span>
              <button type="button" onClick={() => setSelected(new Set(cards.filter(usable).map((card) => card.id)))}>Chọn tất cả</button>
              <button type="button" onClick={() => setSelected(new Set())}>Bỏ chọn hết</button>
            </span>
          </div>
          <ul className="memory-setup__cards" aria-label="Chọn thẻ muốn chơi">
            {cards.map((card) => {
              const ok = usable(card)
              const checked = chosen.has(card.id)
              const missing = [question, answer].filter((field) => !fieldValue(card, field)).map((field) => FIELD_LABELS[field])
              return (
                <li key={card.id}>
                  <label className={`${checked && ok ? 'is-checked' : ''} ${ok ? '' : 'is-disabled'}`}
                    title={ok ? undefined : `Thiếu ${missing.join(', ')}`}>
                    <input type="checkbox" checked={checked && ok} disabled={!ok && !checked} onChange={() => toggleCard(card.id)} />
                    <strong>{fieldValue(card, question) || fieldValue(card, 'KANJI') || '—'}</strong>
                    <small>{ok ? fieldValue(card, answer) : `Thiếu ${missing.join(', ')}`}</small>
                  </label>
                </li>
              )
            })}
          </ul>
        </div>

        <div className="memory-setup__row memory-setup__row--2">
          <div className="memory-setup__group memory-setup__count" aria-live="polite">
            <span className="memory-setup__label">Số thẻ trên bàn</span>
            <strong>{board}</strong>
            {distinct > MAX_PAIRS && <small>Mỗi ván lấy ngẫu nhiên {MAX_PAIRS}/{distinct} thẻ</small>}
          </div>
          <Dropdown<number | null> label="Thời gian" value={timeLimit} options={TIME_LIMITS}
            format={formatTime} onChange={setTimeLimit} recommended={deckCards ? suggestedTime : undefined} />
        </div>

        {(problem || error) && <p className="memory-setup__problem" role="status">{error || problem}</p>}

        <button
          type="button"
          className="memory-btn memory-btn--primary memory-setup__start"
          disabled={starting || !!problem}
          onClick={() => start(timeLimit)}
        >
          {starting ? 'Đang xếp thẻ…' : 'Bắt đầu'}
        </button>
      </section>

      {warning && (
        <div className="memory-setup__dialog" role="alertdialog" aria-modal="true" aria-labelledby="memory-pace-title">
          <div className="memory-setup__dialog-box">
            <p id="memory-pace-title">{warning.message}</p>
            <div className="memory-setup__dialog-actions">
              {warning.fix && warning.fixLabel && (
                <button type="button" className="memory-btn memory-btn--primary" onClick={applyFix}>{warning.fixLabel}</button>
              )}
              <button type="button" className="memory-setup__back" onClick={() => start(timeLimit, true)}>Vẫn chơi</button>
              <button type="button" className="memory-setup__back" onClick={() => setWarning(null)}>Quay lại</button>
            </div>
          </div>
        </div>
      )}
    </main>
  )
}
