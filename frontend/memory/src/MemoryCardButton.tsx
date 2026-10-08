import { motion } from 'framer-motion'
import type { MemoryCard, DeckField } from './cardTypes'
const FIELD_LABELS: Record<DeckField, string> = { KANJI: 'Kanji', HIRAGANA: 'Cách đọc', MEANING: 'Nghĩa', HAN_VIET: 'Hán Việt' }
function isJapaneseField(field?: DeckField) { return field === 'KANJI' || field === 'HIRAGANA' }
function fitText(text: string, japanese: boolean): React.CSSProperties {
  const length = Math.max(1, Array.from(text.trim()).length)
  // Bề rộng trung bình một ký tự theo em: chữ Nhật ~1em, chữ Latin ~0.56em (+ dư cho chỗ ngắt từ).
  const charWidth = japanese ? 1 : 0.62
  let best = { width: 1, lines: 1, score: 0 }
  for (let lines = 1; lines <= (japanese ? 3 : 4); lines += 1) {
    const perLine = Math.ceil(length / lines) * charWidth
    // Thẻ ngang khoảng 1.6 : 1 → 72cqh ≈ 45cqw.
    const score = Math.min(84 / perLine, 45 / (lines * 1.22))
    if (score > best.score) best = { width: perLine, lines, score }
  }
  return { '--fit-w': best.width, '--fit-h': best.lines } as React.CSSProperties
}

export function MemoryCardButton({
  card,
  disabled,
  onFlip,
  reduceMotion,
  feedback
}: {
  card: MemoryCard
  disabled: boolean
  onFlip: (id: string) => void
  reduceMotion: boolean | null
  feedback: 'match' | 'mismatch' | null
}) {
  const revealed = card.state !== 'HIDDEN'
  const matched = card.state === 'MATCHED'
  const faceLabel = card.face ? FIELD_LABELS[card.face] : 'Thẻ'

  return (
    <motion.button
      type="button"
      className={`memory-card ${revealed ? 'is-revealed' : ''} ${matched ? 'is-matched' : ''} ${feedback ? `has-${feedback}-feedback` : ''}`}
      disabled={disabled || revealed}
      onClick={() => onFlip(card.cardInstanceId)}
      aria-label={revealed ? `${faceLabel}: ${card.content ?? ''}` : `Thẻ úp số ${card.position + 1}`}
      animate={{ rotateY: revealed ? 180 : 0, scale: 1 }}
      transition={reduceMotion ? { duration: 0 } : {
        rotateY: { duration: 0.42, ease: [0.22, 1, 0.36, 1] },
        scale: { duration: 0.36, ease: [0.22, 1, 0.36, 1] }
      }}
      whileHover={!disabled && !revealed && !reduceMotion ? { y: -5, scale: 1.025 } : undefined}
      whileTap={!disabled && !revealed && !reduceMotion ? { scale: 0.96 } : undefined}
    >
      <span className="memory-card__back" aria-hidden={revealed}>
        <span className="memory-card__crest">❄</span>
        <span className="memory-card__index">{String(card.position + 1).padStart(2, '0')}</span>
      </span>
      <span className="memory-card__front" aria-hidden={!revealed}>
        <span className="memory-card__face">{faceLabel}</span>
        <span className={`memory-card__content ${isJapaneseField(card.face) ? 'jp' : 'is-latin'}`}
          style={fitText(card.content ?? '—', isJapaneseField(card.face))}>
          {card.content ?? '—'}
        </span>
        {matched && <span className="memory-card__matched">✓ Đã ghép</span>}
      </span>
    </motion.button>
  )
}
