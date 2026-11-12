import { motion } from 'framer-motion'
import { useLayoutEffect, useRef } from 'react'
import type { MemoryCard, DeckField } from './cardTypes'
const FIELD_LABELS: Record<DeckField, string> = { KANJI: 'Kanji', HIRAGANA: 'Cách đọc', MEANING: 'Nghĩa', HAN_VIET: 'Hán Việt' }
function isJapaneseField(field?: DeckField) { return field === 'KANJI' || field === 'HIRAGANA' }

export function MemoryCardButton({
  card,
  disabled,
  onFlip,
  reduceMotion,
  feedback,
  colorGroup,
  shift = 0
}: {
  card: MemoryCard
  disabled: boolean
  onFlip: (id: string) => void
  reduceMotion: boolean | null
  feedback: 'match' | 'mismatch' | null
  colorGroup?: 'question' | 'answer'
  /** Sideways offset in px; centres the cards of a last row that is not full. */
  shift?: number
}) {
  const revealed = card.state !== 'HIDDEN'
  const matched = card.state === 'MATCHED'
  const faceLabel = card.face ? FIELD_LABELS[card.face] : 'Thẻ'
  const contentRef = useRef<HTMLSpanElement>(null)

  // Fit the actual wrapped text to the available card space, including tall mobile cards.
  useLayoutEffect(() => {
    const content = contentRef.current
    const front = content?.parentElement
    if (!revealed || !content || !front) return
    let disposed = false
    const fit = () => {
      if (disposed) return
      const style = getComputedStyle(front)
      const width = front.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight)
      const height = front.clientHeight - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom)
      if (width <= 0 || height <= 0) return
      let low = 1
      // Larger cards get larger text: the cap grows with the card instead of stopping at a fixed size.
      let high = Math.min(isJapaneseField(card.face) ? Math.max(64, width * 0.5) : Math.max(36, width * 0.22), height / 1.25)
      for (let attempt = 0; attempt < 9; attempt += 1) {
        const size = (low + high) / 2
        content.style.fontSize = `${size}px`
        if (content.scrollHeight <= height && content.scrollWidth <= Math.ceil(width)) low = size
        else high = size
      }
      content.style.fontSize = `${Math.floor(low * 10) / 10}px`
    }
    fit()
    const observer = new ResizeObserver(fit)
    observer.observe(front)
    void document.fonts.ready.then(fit)
    return () => { disposed = true; observer.disconnect() }
  }, [revealed, matched, card.content, card.face])

  return (
    <motion.button
      type="button"
      className={`memory-card ${colorGroup ? `memory-card--${colorGroup}` : ''} ${revealed ? 'is-revealed' : ''} ${matched ? 'is-matched' : ''} ${feedback ? `has-${feedback}-feedback` : ''}`}
      disabled={disabled || revealed}
      onClick={() => onFlip(card.cardInstanceId)}
      style={shift ? { left: shift } : undefined}
      aria-label={revealed ? `${faceLabel}: ${card.content ?? ''}${matched ? ', đã ghép' : ''}` : `Thẻ ${colorGroup ? `${faceLabel} ` : ''}úp số ${card.position + 1}`}
      animate={{ rotateY: revealed ? 180 : 0, scale: 1 }}
      transition={reduceMotion ? { duration: 0 } : {
        rotateY: { duration: 0.42, ease: [0.22, 1, 0.36, 1] },
        scale: { duration: 0.36, ease: [0.22, 1, 0.36, 1] }
      }}
      whileHover={!disabled && !revealed && !reduceMotion ? { y: -5, scale: 1.025 } : undefined}
      whileTap={!disabled && !revealed && !reduceMotion ? { scale: 0.96 } : undefined}
    >
      <span className="memory-card__back" aria-hidden={revealed}>
        {colorGroup ? <span className="memory-card__kind">{faceLabel}</span> : <span className="memory-card__crest">❄</span>}
        <span className="memory-card__index">{String(card.position + 1).padStart(2, '0')}</span>
      </span>
      <span className="memory-card__front" aria-hidden={!revealed}>
        <span ref={contentRef} className={`memory-card__content ${isJapaneseField(card.face) ? 'jp' : 'is-latin'}`}>
          {card.content ?? '—'}
        </span>
      </span>
    </motion.button>
  )
}
