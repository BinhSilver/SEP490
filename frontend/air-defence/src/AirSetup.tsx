import { useCallback, useEffect, useId, useRef, useState } from "react";
import { useAirDefenseStore } from "./game/useAirDefenseStore";
import { errorMessage, fetchDeckCards } from "./game/arenaClient";
import { leaveSetup, startSoloMatch } from "./game/matchController";
import type { DeckCardView, DeckField } from "./game/arenaTypes";
import { AirHangar } from "./AirHangar";

/** Khớp AirDefenseServiceImpl.SOLO_DURATIONS và GameRules.AIR_MIN_WORDS của server. */
const DURATIONS = [0, 60, 120, 180, 240, 300, 420, 600, 900, 1200, 1800];
const MIN_WORDS = 5;
/** Ước tính ~8 giây hạ một máy bay (mỗi máy bay một thẻ); mức vừa là mỗi thẻ gặp khoảng 3 lần. */
const SECONDS_PER_TURN = 8;
const GOOD_REPEATS = 3;
const MAX_REPEATS = 6;

function formatDuration(seconds: number): string {
  return seconds === 0 ? "Không giới hạn" : `${seconds / 60} phút`;
}

/** Mức thời gian (có giới hạn) gần nhất với số thẻ × 3 lượt × 8 giây. */
function recommendDuration(cardCount: number): number {
  const target = cardCount * GOOD_REPEATS * SECONDS_PER_TURN;
  return DURATIONS.filter((s) => s > 0)
    .reduce((best, s) => (Math.abs(s - target) < Math.abs(best - target) ? s : best));
}

/** Cảnh báo khi bấm Bắt đầu: quá ngắn (không kịp gặp hết thẻ) hoặc quá dài (một thẻ lặp quá nhiều). */
function paceWarning(cardCount: number, seconds: number): string | null {
  if (seconds === 0 || cardCount === 0) return null;
  const turns = Math.round(seconds / SECONDS_PER_TURN);
  if (turns < cardCount) {
    return `Với ${cardCount} thẻ, ${formatDuration(seconds)} chỉ kịp gặp khoảng ${turns} thẻ.`;
  }
  if (turns / cardCount > MAX_REPEATS) {
    return `Với ${cardCount} thẻ, ${formatDuration(seconds)} sẽ lặp lại mỗi thẻ khoảng ${Math.round(turns / cardCount)} lần.`;
  }
  return null;
}
const FIELDS: DeckField[] = ["KANJI", "HIRAGANA", "MEANING", "HAN_VIET"];
const FIELD_LABELS: Record<DeckField, string> = {
  KANJI: "Kanji",
  HIRAGANA: "Cách đọc",
  MEANING: "Nghĩa",
  HAN_VIET: "Hán Việt",
};

function fieldValue(card: DeckCardView, field: DeckField): string | null {
  switch (field) {
    case "KANJI": return card.kanji;
    case "HIRAGANA": return card.reading;
    case "MEANING": return card.meaning;
    default: return card.hanViet;
  }
}

/** Ô chọn thả xuống tự vẽ (như màn Lật thẻ); lựa chọn bị khoá hiện lý do khi rê chuột, mức gợi ý có nhãn "Gợi ý". */
function Dropdown<T extends string | number>({ label, value, options, format, onChange, disabledReason, recommended }: {
  label: string;
  value: T;
  options: T[];
  format: (option: T) => string;
  onChange: (option: T) => void;
  disabledReason?: (option: T) => string | null;
  recommended?: T | null;
}) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const rootRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLUListElement>(null);
  const id = useId();
  const reason = (option: T) => (disabledReason && option !== value ? disabledReason(option) : null);

  useEffect(() => {
    if (!open) return;
    const onDown = (event: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [open]);

  useEffect(() => {
    if (open) listRef.current?.children[active]?.scrollIntoView({ block: "nearest" });
  }, [open, active]);

  const tag = (option: T) => (recommended != null && option === recommended
    ? <span className="ad-setup__tag">Gợi ý</span> : null);
  const choose = (option: T) => {
    if (reason(option)) return;
    onChange(option);
    setOpen(false);
  };
  const openAtValue = () => { setOpen(true); setActive(Math.max(0, options.indexOf(value))); };
  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === "Escape") { setOpen(false); return; }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (!open) { openAtValue(); return; }
      const step = event.key === "ArrowDown" ? 1 : -1;
      setActive((index) => (index + step + options.length) % options.length);
      return;
    }
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      if (open) choose(options[active]);
      else openAtValue();
    }
  };

  return (
    <div className="ad-setup__group">
      <span className="ad-setup__label" id={`${id}-label`}>{label}</span>
      <div className="ad-dd" ref={rootRef}>
        <button type="button" className="ad-dd__trigger" aria-haspopup="listbox" aria-expanded={open}
          aria-labelledby={`${id}-label`} aria-controls={`${id}-list`} onKeyDown={onKeyDown}
          onClick={() => (open ? setOpen(false) : openAtValue())}>
          <span>{format(value)}{tag(value)}</span>
          <i className="fa-solid fa-chevron-down ad-dd__caret" aria-hidden="true" />
        </button>
        {open && (
          <ul className="ad-dd__list" role="listbox" id={`${id}-list`} aria-labelledby={`${id}-label`} ref={listRef}>
            {options.map((option, index) => {
              const why = reason(option);
              return (
                <li key={String(option)} role="option" aria-selected={option === value} aria-disabled={!!why}
                  title={why ?? undefined}
                  className={`${option === value ? "is-selected" : ""} ${index === active ? "is-active" : ""} ${why ? "is-disabled" : ""}`}
                  onMouseEnter={() => setActive(index)}
                  onMouseDown={(event) => { event.preventDefault(); choose(option); }}>
                  {format(option)}{tag(option)}
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}

/**
 * Cài đặt trước ván luyện tập: hỏi – đáp (chỉ trường mà mọi thẻ đang chọn đều có), thẻ muốn chơi, thời gian.
 * Bấm "Bắt đầu" thì server dựng lại dãy từ và intro đếm ngược 3-2-1.
 */
export function AirSetup() {
  const view = useAirDefenseStore((s) => s.setupView);
  const [cards, setCards] = useState<DeckCardView[] | null>(null);
  const [question, setQuestion] = useState<DeckField>(view?.config.questionField ?? "KANJI");
  const [answer, setAnswer] = useState<DeckField>(view?.config.answerField ?? "MEANING");
  /** null = chưa tự chọn: đi theo mức gợi ý (đổi theo số thẻ đang chọn). */
  const [durationChoice, setDuration] = useState<number | null>(null);
  const [warning, setWarning] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<number> | null>(null);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hangarBusy, setHangarBusy] = useState(true);
  const hangarBusyRef = useRef(true);
  const startingRef = useRef(false);
  const onHangarBusy = useCallback((busy: boolean) => {
    hangarBusyRef.current = busy;
    setHangarBusy(busy);
  }, []);

  const deckId = view?.deckId;
  useEffect(() => {
    if (!deckId) return;
    let alive = true;
    fetchDeckCards(deckId)
      .then((list) => { if (alive) setCards(list); })
      .catch((e) => { if (alive) { setCards([]); setError(errorMessage(e, "Không tải được thẻ của bộ.")); } });
    return () => { alive = false; };
  }, [deckId]);

  if (!view) return null;

  const all = cards ?? [];
  const chosen = selected ?? new Set(all
    .filter((card) => fieldValue(card, view.config.questionField) && fieldValue(card, view.config.answerField))
    .map((card) => card.id));
  const chosenCards = all.filter((card) => chosen.has(card.id));
  const usable = (card: DeckCardView) => !!fieldValue(card, question) && !!fieldValue(card, answer);
  const playable = chosenCards.filter(usable);

  const missingCount = (field: DeckField) => chosenCards.filter((card) => !fieldValue(card, field)).length;
  const fieldReason = (field: DeckField, other: DeckField) => {
    if (field === other) return "Hỏi và đáp phải khác nhau";
    const missing = missingCount(field);
    return missing ? `${missing} thẻ đang chọn chưa có ${FIELD_LABELS[field]}` : null;
  };

  const toggleCard = (id: number) => {
    const next = new Set(chosen);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setSelected(next);
  };

  const problem = cards === null ? "Đang tải thẻ của bộ…"
    : playable.length < MIN_WORDS ? `Chọn ít nhất ${MIN_WORDS} thẻ để chơi (đang chọn ${playable.length}).`
      : null;

  const suggested = recommendDuration(Math.max(playable.length, 1));
  const duration = durationChoice ?? suggested;

  const onStart = async (seconds: number, confirmed = false) => {
    if (startingRef.current || hangarBusyRef.current || problem) return;
    const warn = confirmed ? null : paceWarning(playable.length, seconds);
    if (warn) {
      setWarning(warn);
      return;
    }
    setWarning(null);
    setDuration(seconds);
    setStarting(true);
    startingRef.current = true;
    setError(null);
    try {
      await startSoloMatch({
        questionField: question,
        answerField: answer,
        cardIds: playable.map((card) => card.id),
        durationSeconds: seconds,
      });
    } catch (e) {
      setError(errorMessage(e, "Không bắt đầu được. Vui lòng thử lại."));
      setStarting(false);
      startingRef.current = false;
    }
  };

  return (
    <div className="ad-app ad-setup">
      <section className="ad-setup__panel" aria-labelledby="ad-setup-title">
        <header className="ad-setup__head">
          <button type="button" className="ad-setup__back" onClick={() => void leaveSetup()}>
            Quay về
          </button>
          <div>
            <p className="ad-setup__kicker">Luyện tập một mình</p>
            <h1 id="ad-setup-title">Bắn máy bay</h1>
            <p className="ad-setup__deck">{view.deckTitle}</p>
          </div>
        </header>

        <AirHangar starting={starting} onBusyChange={onHangarBusy} />

        <div className="ad-setup__row">
          <Dropdown<DeckField> label="Hỏi bằng" value={question} options={FIELDS} format={(f) => FIELD_LABELS[f]}
            disabledReason={(f) => fieldReason(f, answer)} onChange={setQuestion} />
          <Dropdown<DeckField> label="Gõ đáp án bằng" value={answer} options={FIELDS} format={(f) => FIELD_LABELS[f]}
            disabledReason={(f) => fieldReason(f, question)} onChange={setAnswer} />
          <Dropdown<number> label="Thời gian" value={duration} options={DURATIONS}
            format={formatDuration} onChange={setDuration} recommended={cards === null ? null : suggested} />
        </div>

        <div className="ad-setup__group">
          <div className="ad-setup__cards-head">
            <span className="ad-setup__label">Thẻ · {chosenCards.length}/{all.length} đã chọn</span>
            <span>
              <button type="button" onClick={() => setSelected(new Set(all.filter(usable).map((card) => card.id)))}>Chọn tất cả</button>
              <button type="button" onClick={() => setSelected(new Set())}>Bỏ chọn hết</button>
            </span>
          </div>
          <ul className="ad-setup__cards" aria-label="Chọn thẻ muốn chơi">
            {all.map((card) => {
              const ok = usable(card);
              const checked = chosen.has(card.id);
              const missing = [question, answer].filter((f) => !fieldValue(card, f)).map((f) => FIELD_LABELS[f]);
              return (
                <li key={card.id}>
                  <label className={`${checked && ok ? "is-checked" : ""} ${ok ? "" : "is-disabled"}`}
                    title={ok ? undefined : `Thiếu ${missing.join(", ")}`}>
                    <input type="checkbox" checked={checked && ok} disabled={!ok && !checked} onChange={() => toggleCard(card.id)} />
                    <strong>{fieldValue(card, question) || fieldValue(card, "KANJI") || "—"}</strong>
                    <small>{ok ? fieldValue(card, answer) : `Thiếu ${missing.join(", ")}`}</small>
                  </label>
                </li>
              );
            })}
          </ul>
        </div>

        {(problem || error) && <p className="ad-setup__problem" role="status">{error || problem}</p>}

        <button type="button" className="ad-setup__start" disabled={starting || hangarBusy || !!problem} onClick={() => void onStart(duration)}>
          {starting ? "Đang chuẩn bị…" : hangarBusy ? "Đang cập nhật máy bay…" : "Bắt đầu"}
        </button>
      </section>

      {warning && (
        <div className="ad-setup__dialog" role="alertdialog" aria-modal="true" aria-labelledby="ad-pace-title">
          <div className="ad-setup__dialog-box">
            <p id="ad-pace-title">{warning}</p>
            <div className="ad-setup__dialog-actions">
              {suggested !== duration && (
                <button type="button" className="ad-setup__start" onClick={() => void onStart(suggested, true)}>
                  Đổi sang {formatDuration(suggested)}
                </button>
              )}
              <button type="button" className="ad-setup__back" onClick={() => void onStart(duration, true)}>Vẫn chơi</button>
              <button type="button" className="ad-setup__back" onClick={() => setWarning(null)}>Quay lại</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
