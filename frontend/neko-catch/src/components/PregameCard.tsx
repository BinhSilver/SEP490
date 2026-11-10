import React, { useEffect, useId, useRef, useState } from 'react';
import type { DeckCardView, DeckField, NekoPlayerView, NekoStartOptions, NekoStateView } from '../arena/types';
import { assetUrl } from '../assets';
import type { CatSkinId } from '../graphics/CatSpriteSheet';
import { CAT_SKINS } from '../graphics/CatSpriteSheet';
import { difficultyLabel, DISPLAY_FONT_STACK, VI_FONT_STACK } from '../lib/text';
import { MOBILE_GAME_MAX_WIDTH, MOBILE_MAX_FISH } from '../lib/playfield';
import { durationLabel, recommendDuration, SECONDS_PER_TURN, SOLO_DURATIONS as DURATIONS } from '../lib/setup';
import { Avatar } from './Avatar';
import { ArrowLeftIcon, MaximizeIcon as Maximize, MinimizeIcon as Minimize } from './Icons';
import { LobbyCat } from './LobbyCat';
import { ResultCatCanvas } from './ResultCatCanvas';

interface PregameCardProps {
  state: NekoStateView;
  me: NekoPlayerView | null;
  opponent: NekoPlayerView | null;
  /** Whole seconds until startAt (3-2-1), or null once the match has started. */
  countdown: number | null;
  /** Luyện tập: chưa bấm "Bắt đầu" thì hiện phần cài đặt trước trận thay cho đồng hồ đếm ngược. */
  awaitingStart: boolean;
  /** Thẻ của bộ (null khi đang tải) để chọn cặp hỏi – đáp và thẻ muốn chơi. */
  deckCards: DeckCardView[] | null;
  /** Trang phục mèo trong Cửa hàng (null khi đang tải); có thể mua và dùng ngay. */
  skinOptions: SkinOption[] | null;
  skinBalance: number | null;
  skinBusy: string | null;
  skinError: string | null;
  onSkinRetry: () => void;
  onSkinChange: (skin: CatSkinId) => Promise<boolean>;
  starting: boolean;
  assetsReady: boolean;
  assetError: string | null;
  onAssetRetry: () => void;
  onStart: (options: NekoStartOptions) => void;
  skin: CatSkinId;
  /** Luyện tập, trước khi bấm "Bắt đầu": rời trận và quay về trang trước. */
  onBack: () => void;
  isFullscreen: boolean;
  onToggleFullscreen: () => void;
}

export interface SkinOption {
  id: CatSkinId;
  code: string;
  name: string;
  image: string;
  owned: boolean;
  price: number;
}

/** Mua và dùng trang phục ngay ở màn chuẩn bị. */
const SkinPanel: React.FC<{
  skin: CatSkinId;
  options: SkinOption[] | null;
  balance: number | null;
  busy: string | null;
  disabled: boolean;
  error: string | null;
  onRetry: () => void;
  onChange: (skin: CatSkinId) => Promise<boolean>;
}> = ({
  skin,
  options,
  balance,
  busy,
  disabled,
  error,
  onRetry,
  onChange,
}) => {
  const [pendingCode, setPendingCode] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const [placement, setPlacement] = useState<{ up: boolean; maxHeight: number }>({ up: false, maxHeight: 240 });
  const pickerRef = useRef<HTMLDivElement>(null);
  const listId = useId();
  /** Thẻ cài đặt cắt phần tràn: mở về phía còn nhiều chỗ hơn và giới hạn chiều cao cho vừa. */
  const toggleOpen = () => {
    const picker = pickerRef.current;
    if (!open && picker) {
      let clip: HTMLElement | null = picker.parentElement;
      while (clip && getComputedStyle(clip).overflow === 'visible') clip = clip.parentElement;
      const bound = clip?.getBoundingClientRect() ?? { top: 0, bottom: window.innerHeight };
      const rect = picker.getBoundingClientRect();
      const below = Math.min(bound.bottom, window.innerHeight) - rect.bottom - 12;
      const above = rect.top - Math.max(bound.top, 0) - 12;
      const up = below < 240 && above > below;
      setPlacement({ up, maxHeight: Math.max(120, Math.min(240, up ? above : below)) });
    }
    setOpen(value => !value);
  };
  useEffect(() => {
    if (!open) return;
    const onDown = (event: MouseEvent) => {
      if (pickerRef.current && !pickerRef.current.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);
  const confirmRef = useRef<HTMLDialogElement>(null);
  const pending = options?.find(option => option.code === pendingCode && !option.owned);
  useEffect(() => {
    const dialog = confirmRef.current;
    if (!dialog) return;
    if (pending && !dialog.open) dialog.showModal();
    else if (!pending && dialog.open) dialog.close();
  }, [pending]);
  const name = options?.find(option => option.id === skin)?.name ?? (CAT_SKINS.find(item => item.id === skin) ?? CAT_SKINS[0]).name;
  return (
    <section className="neko-skins w-full p-2.5 rounded-2xl bg-white/5 border border-white/10 text-left" aria-label="Trang phục" aria-busy={!!busy}>
      <div className="neko-skins__header">
        <h3>Trang phục</h3>
        <output aria-label="Số dư" aria-live="polite">{balance === null ? '…' : balance.toLocaleString('vi-VN')} CP</output>
      </div>
      <div className="neko-skins__preview relative w-full h-44 overflow-hidden rounded-xl border border-amber-300/30 bg-slate-950">
        <img src={assetUrl('/sprites/tasser_hand_drawn/bg_panoramic_composite.png')} alt="" aria-hidden="true"
          className="absolute inset-0 h-full w-full object-cover opacity-80" />
        <div className="absolute inset-0 bg-gradient-to-t from-slate-950/70 via-transparent to-transparent" />
        <div className="absolute inset-x-0 bottom-1 flex justify-center pointer-events-none">
          <LobbyCat skinId={skin} className="h-36" />
        </div>
        <p className="absolute left-2 top-2 rounded-lg bg-slate-950/80 px-2 py-1 text-[10px] font-black uppercase tracking-wider text-amber-300">{name}</p>
      </div>
      {/* Máy tính: ô thả xuống; điện thoại (CSS) ẩn nút này và luôn hiện dải trang phục kéo ngang. */}
      <div className="neko-skins__picker" ref={pickerRef}
        onKeyDown={event => { if (event.key === 'Escape' && open) { event.stopPropagation(); setOpen(false); } }}>
        <button type="button" className="neko-skins__trigger" aria-expanded={open} aria-controls={listId}
          disabled={options === null} onClick={toggleOpen}>
          <span className="neko-skins__name">{name}</span>
          <span className="neko-skins__action">Đổi</span>
          <svg className="neko-skins__caret" viewBox="0 0 12 12" aria-hidden="true">
            <path d="M2 4l4 4 4-4" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
      <div id={listId} className={`neko-skins__list ${open ? 'is-open' : ''} ${placement.up ? 'is-up' : ''}`}
        style={{ '--neko-skins-max': `${placement.maxHeight}px` } as React.CSSProperties}
        role="group" aria-label="Chọn hoặc mua trang phục">
        {(options ?? []).map(option => {
          const selected = option.id === skin;
          const affordable = balance !== null && balance >= option.price;
          const label = busy === option.code ? 'Đang lưu…' : selected ? 'Đang dùng' : option.owned ? 'Dùng' : `Mua · ${option.price} CP`;
          return (
            <button key={option.code} type="button" aria-pressed={selected}
              disabled={disabled || !!busy || selected || (!option.owned && !affordable)}
              onClick={() => {
                setOpen(false);
                if (option.owned) { setPendingCode(null); void onChange(option.id); } else setPendingCode(option.code);
              }}
              title={!option.owned && !affordable ? `Cần ${option.price} CP để mua` : undefined}
              aria-label={`${option.name}, ${label}${!option.owned && !affordable ? ', chưa đủ CP' : ''}`}
              className={`neko-skins__item ${selected ? 'is-selected' : ''}`}>
              <img className="neko-skins__image" src={option.image} alt="" loading="lazy" />
              <span className="neko-skins__name">{option.name}</span>
              <span className="neko-skins__action">{label}</span>
            </button>
          );
        })}
      </div>
      </div>
      {options === null && !error && <p className="mt-2 text-[11px] text-white/50">Đang tải trang phục…</p>}
      <dialog ref={confirmRef} className="neko-skin-purchase" aria-labelledby="neko-skin-purchase-title"
        aria-describedby="neko-skin-purchase-details"
        onCancel={event => { if (busy) event.preventDefault(); else setPendingCode(null); }}
        onClose={() => setPendingCode(null)}>
        {pending && <>
          <h3 id="neko-skin-purchase-title">Xác nhận mua skin</h3>
          <img src={pending.image} alt="" />
          <p id="neko-skin-purchase-details">Mua <strong>{pending.name}</strong> với <strong>{pending.price} CP</strong>?</p>
          <p className="neko-skin-purchase__balance">Số dư sau khi mua: {Math.max(0, (balance ?? 0) - pending.price).toLocaleString('vi-VN')} CP</p>
          {error && <p className="neko-skins__error" role="alert">{error}</p>}
          <div className="neko-skin-purchase__actions">
            <button type="button" autoFocus disabled={disabled || !!busy} onClick={() => setPendingCode(null)}>Hủy</button>
            <button type="button" disabled={disabled || !!busy || balance === null || balance < pending.price}
              onClick={async () => { if (await onChange(pending.id)) setPendingCode(null); }}>
              {busy ? 'Đang mua…' : `Mua · ${pending.price} CP`}
            </button>
          </div>
        </>}
      </dialog>
      {error && <div className="neko-skins__error" role="alert">
        <p>{error}</p>
        {!options && <button type="button" onClick={onRetry}>Thử lại</button>}
      </div>}
    </section>
  );
};

/** Giống GameRules.NEKO_DURATIONS / NEKO_MIN_WORDS và giới hạn của NekoCatchServiceImpl. */
const LIVES = [1, 2, 3, 4, 5];
const MIN_CARDS = 4;
const MAX_CHOICES = 7;
/** Ước tính ~7 giây một câu (một đợt cá rơi); mức vừa là mỗi thẻ gặp khoảng 2 lần. */
const MAX_REPEATS = 6;

/** Cảnh báo khi bấm Bắt đầu: quá ngắn (không kịp gặp hết thẻ) hoặc quá dài (một thẻ lặp quá nhiều). */
function paceWarning(cardCount: number, seconds: number): string | null {
  if (cardCount === 0) return null;
  const turns = Math.round(seconds / SECONDS_PER_TURN);
  if (turns < cardCount) return `Với ${cardCount} thẻ, ${seconds} giây chỉ kịp gặp khoảng ${turns} thẻ.`;
  if (turns / cardCount > MAX_REPEATS) {
    return `Với ${cardCount} thẻ, ${seconds} giây sẽ lặp lại mỗi thẻ khoảng ${Math.round(turns / cardCount)} lần.`;
  }
  return null;
}
const FIELDS: DeckField[] = ['KANJI', 'HIRAGANA', 'MEANING', 'HAN_VIET'];
const FIELD_LABELS: Record<DeckField, string> = {
  KANJI: 'Kanji',
  HIRAGANA: 'Cách đọc',
  MEANING: 'Nghĩa',
  HAN_VIET: 'Hán Việt',
};

function fieldValue(card: DeckCardView, field: DeckField): string | null {
  switch (field) {
    case 'KANJI': return card.kanji;
    case 'HIRAGANA': return card.reading;
    case 'MEANING': return card.meaning;
    default: return card.hanViet;
  }
}

/** Một nhóm lựa chọn dạng nút bấm (radiogroup); nút bị khoá có lý do ở tooltip. */
function OptionGroup<T extends string | number>({ label, value, options, format, onChange, disabledReason, stretch = false }: {
  label: string;
  value: T;
  options: T[];
  format: (option: T) => string;
  onChange: (option: T) => void;
  disabledReason?: (option: T) => string | null;
  /** Các nút giãn đều lấp kín ô, không xuống dòng. */
  stretch?: boolean;
}) {
  return (
    <div className="p-2.5 rounded-2xl bg-white/5 border border-white/10 text-left">
      <span className="block text-[10px] text-amber-300/70 uppercase tracking-widest mb-1.5">{label}</span>
      <div className={`flex gap-1 ${stretch ? '' : 'flex-wrap'}`} role="radiogroup" aria-label={label}>
        {options.map(option => {
          const selected = option === value;
          const reason = disabledReason ? disabledReason(option) : null;
          return (
            <button
              key={String(option)}
              type="button"
              role="radio"
              aria-checked={selected}
              disabled={!!reason && !selected}
              title={reason ?? undefined}
              onClick={() => onChange(option)}
              className={`relative ${stretch ? 'flex-1 min-w-0 px-1' : 'min-w-[36px] px-2'} min-h-[32px] rounded-lg text-xs font-black transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-300 disabled:opacity-35 disabled:cursor-not-allowed ${
                selected ? 'bg-amber-400 text-slate-950' : 'bg-white/10 text-white/80 hover:bg-white/20'
              }`}
            >
              {format(option)}
            </button>
          );
        })}
      </div>
    </div>
  );
}

/**
 * Ô chọn thả xuống (như Bắn máy bay, Lật thẻ): bấm vào để xổ danh sách; mức hợp với số thẻ có nhãn "Gợi ý".
 * Danh sách mở lên trên vì ô nằm sát đáy thẻ cài đặt (thẻ cắt phần tràn).
 */
function DropdownGroup<T extends string | number>({ label, value, options, format, onChange, recommended, disabled = false }: {
  label: string;
  value: T;
  options: T[];
  format: (option: T) => string;
  onChange: (option: T) => void;
  recommended?: T | null;
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const rootRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLUListElement>(null);
  const id = useId();

  useEffect(() => {
    if (!open) return;
    const onDown = (event: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  useEffect(() => {
    if (open) listRef.current?.children[active]?.scrollIntoView({ block: 'nearest' });
  }, [open, active]);

  useEffect(() => { if (disabled) setOpen(false); }, [disabled]);

  const tag = (option: T) => (recommended != null && option === recommended
    ? <span className="shrink-0 whitespace-nowrap px-1.5 rounded-full bg-emerald-400 text-slate-950 text-[9px] font-black leading-[16px]">Gợi ý</span>
    : null);
  const choose = (option: T) => { onChange(option); setOpen(false); };
  const openAtValue = () => { setOpen(true); setActive(Math.max(0, options.indexOf(value))); };
  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape') { setOpen(false); return; }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      if (!open) { openAtValue(); return; }
      const step = event.key === 'ArrowDown' ? 1 : -1;
      setActive(index => (index + step + options.length) % options.length);
      return;
    }
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      if (open) choose(options[active]);
      else openAtValue();
    }
  };

  return (
    <div className="p-2.5 rounded-2xl bg-white/5 border border-white/10 text-left">
      <span className="block text-[10px] text-amber-300/70 uppercase tracking-widest mb-1.5" id={`${id}-label`}>{label}</span>
      <div className="relative" ref={rootRef}>
        <button type="button" disabled={disabled} aria-haspopup="listbox" aria-expanded={open}
          aria-labelledby={`${id}-label`} aria-controls={`${id}-list`} onKeyDown={onKeyDown}
          onClick={() => (open ? setOpen(false) : openAtValue())}
          className={`w-full min-h-[36px] flex items-center justify-between gap-2 px-3 rounded-lg border text-xs font-black text-white bg-white/10 hover:bg-white/20 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-300 disabled:opacity-50 disabled:cursor-not-allowed ${
            open ? 'border-amber-300' : 'border-white/10'
          }`}>
          <span className="flex items-center gap-1.5 min-w-0"><span className="min-w-0 truncate">{format(value)}</span>{tag(value)}</span>
          <svg className={`w-3 h-3 shrink-0 text-amber-300 transition-transform ${open ? 'rotate-180' : ''}`} viewBox="0 0 12 12" aria-hidden="true">
            <path d="M2 4l4 4 4-4" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
        {open && (
          <ul ref={listRef} id={`${id}-list`} role="listbox" aria-labelledby={`${id}-label`}
            className="absolute left-0 right-0 bottom-[calc(100%+4px)] z-30 max-h-60 overflow-y-auto custom-scrollbar p-1 rounded-xl border border-white/15 bg-[#1e1638] shadow-[0_16px_40px_rgba(0,0,0,.5)]">
            {options.map((option, index) => {
              const selected = option === value;
              return (
                <li key={String(option)} role="option" aria-selected={selected}
                  onMouseEnter={() => setActive(index)}
                  onMouseDown={event => { event.preventDefault(); choose(option); }}
                  className={`flex items-center justify-between gap-2 min-h-[32px] px-2.5 rounded-lg text-xs font-black cursor-pointer ${
                    selected ? 'bg-amber-400 text-slate-950' : index === active ? 'bg-white/15 text-white' : 'text-white/80'
                  }`}>
                  <span>{format(option)}</span>{tag(option)}
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}

const PlayerChip: React.FC<{ player: NekoPlayerView; label: string; accent: string }> = ({ player, label, accent }) => (
  <div className="flex-1 min-w-0 flex items-center gap-2 px-3 py-2 rounded-2xl bg-white/5 border border-white/10">
    <Avatar name={player.displayName} url={player.avatarUrl} size={34} ring={accent} />
    <div className="min-w-0 text-left">
      <p className="text-[10px] font-black uppercase tracking-wider" style={{ color: accent }}>{label}</p>
      <p className="text-sm font-bold text-white truncate" title={player.displayName}>{player.displayName}</p>
    </div>
    {!player.online && <span className="ml-auto text-[10px] font-bold text-slate-300 shrink-0">Chưa vào</span>}
  </div>
);

/** Cài đặt trước trận luyện tập: cặp hỏi – đáp, thẻ muốn chơi, thời gian, số tim, số cá. */
const SoloSetup: React.FC<{
  state: NekoStateView;
  deckCards: DeckCardView[] | null;
  starting: boolean;
  skinSaving: boolean;
  assetsReady: boolean;
  assetError: string | null;
  onAssetRetry: () => void;
  onStart: (options: NekoStartOptions) => void;
}> = ({ state, deckCards, starting, skinSaving, assetsReady, assetError, onAssetRetry, onStart }) => {
  const { config } = state;
  const [question, setQuestion] = useState<DeckField>(config.questionField);
  const [answer, setAnswer] = useState<DeckField>(config.answerField);
  /** null = chưa tự chọn: thời gian đi theo mức gợi ý (đổi theo số thẻ đang chọn). */
  const [durationChoice, setDuration] = useState<number | null>(null);
  const [warning, setWarning] = useState<string | null>(null);
  const [lives, setLives] = useState(config.lives);
  const [choices, setChoices] = useState(config.choices);
  const [mobile, setMobile] = useState(() => window.innerWidth <= MOBILE_GAME_MAX_WIDTH);
  useEffect(() => {
    const resize = () => setMobile(window.innerWidth <= MOBILE_GAME_MAX_WIDTH);
    window.addEventListener('resize', resize);
    return () => window.removeEventListener('resize', resize);
  }, []);
  // Lúc đầu chọn mọi thẻ đủ cặp trường hiện tại (đúng như trận đã mở).
  const [selected, setSelected] = useState<Set<number> | null>(null);
  const cards = deckCards ?? [];
  const chosen = selected ?? new Set(cards
    .filter(card => fieldValue(card, config.questionField) && fieldValue(card, config.answerField))
    .map(card => card.id));
  const chosenCards = cards.filter(card => chosen.has(card.id));

  // Một trường chỉ dùng được khi MỌI thẻ đang chọn đều có trường đó.
  const missingCount = (field: DeckField) => chosenCards.filter(card => !fieldValue(card, field)).length;
  const fieldReason = (field: DeckField, other: DeckField) => {
    if (field === other) return 'Hỏi và đáp phải khác nhau';
    const missing = missingCount(field);
    return missing ? `${missing} thẻ đang chọn chưa có ${FIELD_LABELS[field]}` : null;
  };

  // Cá sai lấy từ đáp án của cả bộ (như server), nên số cá tối đa tính trên mọi thẻ của bộ chứ không chỉ thẻ đang chọn.
  const deckAnswers = new Set(cards.map(card => (fieldValue(card, answer) || '').toLowerCase()).filter(Boolean));
  const maxChoices = Math.min(mobile ? MOBILE_MAX_FISH : MAX_CHOICES,
    Math.max(2, deckCards && deckCards.length ? Math.min(MAX_CHOICES, deckAnswers.size) : config.maxChoices));
  const choiceOptions = Array.from({ length: maxChoices - 1 }, (_, i) => i + 2).filter(n => n >= Math.min(3, maxChoices));
  const fishCount = Math.min(choices, maxChoices);

  const toggleCard = (id: number) => {
    const next = new Set(chosen);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setSelected(next);
  };
  const usable = (card: DeckCardView) => !!fieldValue(card, question) && !!fieldValue(card, answer);
  const selectAllUsable = () => setSelected(new Set(cards.filter(usable).map(card => card.id)));

  const problem = deckCards === null ? 'Đang tải thẻ của bộ…'
    : chosenCards.length < MIN_CARDS ? `Chọn ít nhất ${MIN_CARDS} thẻ (đang chọn ${chosenCards.length}).`
      : null;

  const suggested = recommendDuration(Math.max(chosenCards.length, 1));
  const durationSeconds = durationChoice ?? suggested;
  const start = (seconds: number, confirmed = false) => {
    const warn = confirmed ? null : paceWarning(chosenCards.length, seconds);
    setWarning(warn);
    if (warn) return;
    setDuration(seconds);
    onStart({
      questionField: question,
      answerField: answer,
      cardIds: chosenCards.map(card => card.id),
      durationSeconds: seconds,
      lives,
      choices: fishCount,
    });
  };

  return (
    <>
      <div className="w-full grid grid-cols-1 sm:grid-cols-2 gap-2 mt-4 text-xs">
        <OptionGroup<DeckField> label="Hỏi bằng" value={question} options={FIELDS} format={field => FIELD_LABELS[field]}
          disabledReason={field => fieldReason(field, answer)} onChange={setQuestion} />
        <OptionGroup<DeckField> label="Đáp bằng" value={answer} options={FIELDS} format={field => FIELD_LABELS[field]}
          disabledReason={field => fieldReason(field, question)} onChange={setAnswer} />
      </div>

      <div className="w-full mt-2 p-2.5 rounded-2xl bg-white/5 border border-white/10 text-left">
        <div className="flex items-center justify-between gap-2 mb-1.5">
          <span className="text-[10px] text-amber-300/70 uppercase tracking-widest">
            Thẻ · {chosenCards.length}/{cards.length} đã chọn
          </span>
          <span className="flex gap-1">
            <button type="button" onClick={selectAllUsable}
              className="px-2 py-1 rounded-lg text-[11px] font-bold text-white/80 bg-white/10 hover:bg-white/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-300">
              Chọn tất cả
            </button>
            <button type="button" onClick={() => setSelected(new Set())}
              className="px-2 py-1 rounded-lg text-[11px] font-bold text-white/80 bg-white/10 hover:bg-white/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-300">
              Bỏ chọn hết
            </button>
          </span>
        </div>
        <ul className="grid grid-cols-1 sm:grid-cols-2 gap-1 max-h-44 overflow-y-auto custom-scrollbar pr-1" aria-label="Chọn thẻ muốn chơi">
          {cards.map(card => {
            const ok = usable(card);
            const missing = [question, answer].filter(field => !fieldValue(card, field)).map(field => FIELD_LABELS[field]);
            const checked = chosen.has(card.id);
            return (
              <li key={card.id}>
                <label className={`flex items-center gap-2 min-h-[36px] px-2 rounded-lg border ${
                  checked ? 'bg-amber-400/15 border-amber-300/50' : 'bg-white/[0.03] border-white/10'
                } ${ok ? 'cursor-pointer' : 'opacity-45 cursor-not-allowed'}`}
                  title={ok ? undefined : `Thiếu ${missing.join(', ')}`}>
                  <input type="checkbox" className="accent-amber-400 w-4 h-4 shrink-0" checked={checked && ok}
                    disabled={!ok && !checked} onChange={() => toggleCard(card.id)} />
                  <span className="min-w-0 flex-1 truncate text-[13px] font-bold text-white">
                    {fieldValue(card, question) || fieldValue(card, 'KANJI') || '—'}
                  </span>
                  <span className="min-w-0 truncate text-[11px] text-white/60">
                    {ok ? fieldValue(card, answer) : `Thiếu ${missing.join(', ')}`}
                  </span>
                </label>
              </li>
            );
          })}
        </ul>
      </div>

      <div className="neko-setup__quick-mobile">
        <label><span>Thời gian</span>
          <select aria-label="Thời gian" value={durationSeconds} onChange={event => setDuration(Number(event.target.value))}
            disabled={starting || skinSaving} title={`Gợi ý: ${suggested}s`}>
            {DURATIONS.map(seconds => <option key={seconds} value={seconds}>{durationLabel(seconds)}{seconds === suggested ? ' · Gợi ý' : ''}</option>)}
          </select>
        </label>
        <label><span>Số tim</span>
          <select aria-label="Số tim" value={lives} onChange={event => setLives(Number(event.target.value))} disabled={starting || skinSaving}>
            {LIVES.map(count => <option key={count} value={count}>{count}</option>)}
          </select>
        </label>
        <label><span>Số cá/lượt</span>
          <select aria-label="Số cá/lượt" value={fishCount} onChange={event => setChoices(Number(event.target.value))} disabled={starting || skinSaving}>
            {choiceOptions.map(count => <option key={count} value={count}>{count}</option>)}
          </select>
        </label>
      </div>
      <div className="neko-setup__quick-desktop w-full grid grid-cols-1 sm:grid-cols-[minmax(0,4fr)_minmax(0,5fr)_minmax(0,5fr)] gap-2 mt-2 text-xs">
        <DropdownGroup label="Thời gian" value={durationSeconds} options={DURATIONS} format={durationLabel} onChange={setDuration}
          recommended={deckCards ? suggested : null} disabled={starting || skinSaving} />
        <OptionGroup label="Số tim" value={lives} options={LIVES} format={String} onChange={setLives} stretch />
        <OptionGroup label="Số cá / lượt" value={fishCount} options={choiceOptions} format={String} onChange={setChoices} stretch />
      </div>

      {problem && <p className="w-full mt-2 text-xs font-bold text-amber-200" role="status">{problem}</p>}
      {!assetsReady && <div className="mt-2 text-xs text-amber-100 text-center" role={assetError ? 'alert' : 'status'}>
        <p>{assetError ?? 'Đang tải hình trò chơi…'}</p>
        {assetError && <button type="button" onClick={onAssetRetry} className="mt-2 min-h-[44px] px-4 rounded-lg bg-white/10 font-bold">Thử tải lại</button>}
      </div>}

      <button
        type="button"
        onClick={() => start(durationSeconds)}
        disabled={starting || skinSaving || !assetsReady || !!problem}
        autoFocus
        className="self-center w-full sm:w-auto sm:min-w-[14rem] mt-3 px-8 py-2.5 min-h-[44px] rounded-xl text-base font-black uppercase tracking-wider text-slate-950 bg-gradient-to-r from-amber-300 to-orange-400 shadow-[0_4px_0_#b45309] hover:brightness-105 active:translate-y-0.5 active:shadow-[0_2px_0_#b45309] disabled:opacity-50 disabled:cursor-not-allowed transition-all focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-amber-200"
        style={{ fontFamily: DISPLAY_FONT_STACK }}
      >
        {starting ? 'Đang vào…' : 'Bắt đầu'}
      </button>

      {warning && (
        <div className="fixed inset-0 z-50 grid place-items-center p-4 bg-black/60" role="alertdialog" aria-modal="true" aria-labelledby="neko-pace-title">
          <div className="w-full max-w-md p-5 rounded-2xl text-center border-2 border-amber-300/40"
            style={{ background: 'linear-gradient(160deg, rgba(40,20,85,0.99), rgba(20,8,50,0.99))' }}>
            <p id="neko-pace-title" className="mb-4 text-base font-bold text-white leading-relaxed">{warning}</p>
            <div className="flex flex-wrap justify-center gap-2">
              {suggested !== durationSeconds && (
                <button type="button" onClick={() => start(suggested, true)}
                  className="px-4 min-h-[40px] rounded-xl text-sm font-black text-slate-950 bg-gradient-to-r from-amber-300 to-orange-400">
                  Đổi sang {suggested} giây
                </button>
              )}
              <button type="button" onClick={() => start(durationSeconds, true)}
                className="px-4 min-h-[40px] rounded-xl text-sm font-bold text-white bg-white/10 border border-white/20 hover:bg-white/20">
                Vẫn chơi
              </button>
              <button type="button" onClick={() => setWarning(null)}
                className="px-4 min-h-[40px] rounded-xl text-sm font-bold text-white bg-white/10 border border-white/20 hover:bg-white/20">
                Quay lại
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
};


/** Đấu tay đôi: thông tin trận + đếm ngược tới startAt. Luyện tập: cài đặt trước trận rồi bấm "Bắt đầu". */
export const PregameCard: React.FC<PregameCardProps> = ({
  state,
  me,
  opponent,
  countdown,
  awaitingStart,
  deckCards,
  skinOptions,
  skinBalance,
  skinBusy,
  skinError,
  onSkinRetry,
  onSkinChange,
  starting,
  assetsReady,
  assetError,
  onAssetRetry,
  onStart,
  skin,
  onBack,
  isFullscreen,
  onToggleFullscreen,
}) => {
  const { config } = state;
  const versus = state.mode === 'VERSUS';
  const skinInfo = CAT_SKINS.find(item => item.id === skin) ?? CAT_SKINS[0];

  return (
    <div className="absolute inset-0 z-30 flex items-center justify-center p-4 bg-black/65 backdrop-blur-md">
      <div
        role="dialog"
        aria-modal="false"
        aria-labelledby="pregame-title"
        aria-describedby={awaitingStart ? undefined : 'pregame-countdown'}
        className="relative flex flex-col items-center text-center p-5 sm:p-6 rounded-3xl w-full max-h-[calc(100dvh-2rem)] overflow-x-hidden overflow-y-auto custom-scrollbar animate-fade-in"
        style={{
          maxWidth: awaitingStart ? 'min(58rem, calc(100vw - 2rem))' : 'min(38rem, calc(100vw - 2rem))',
          background: 'linear-gradient(160deg, rgba(30,15,70,0.97), rgba(15,5,40,0.99))',
          border: '2px solid rgba(255,159,67,0.4)',
          boxShadow: '0 0 60px rgba(255,159,67,0.25)',
        }}
      >
        {/* Góc trên: Quay về (luyện tập, trước khi bắt đầu) và Toàn màn hình. */}
        {awaitingStart && (
          <button
            type="button"
            onClick={onBack}
            className="absolute left-3 top-3 inline-flex items-center gap-1 h-9 pl-2 pr-3 rounded-xl text-xs font-bold text-white/80 bg-white/10 border border-white/15 hover:bg-white/20 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400"
          >
            <ArrowLeftIcon className="w-4 h-4" /> Quay về
          </button>
        )}
        <button
          type="button"
          onClick={onToggleFullscreen}
          aria-label={isFullscreen ? 'Thoát toàn màn hình' : 'Mở toàn màn hình'}
          title={isFullscreen ? 'Thoát toàn màn hình' : 'Toàn màn hình'}
          className="absolute right-3 top-3 grid place-items-center w-9 h-9 rounded-xl text-white/80 bg-white/10 border border-white/15 hover:bg-white/20 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400"
        >
          {isFullscreen ? <Minimize className="w-4 h-4" /> : <Maximize className="w-4 h-4" />}
        </button>
        <p className="text-[10px] font-black uppercase tracking-[0.22em] text-amber-300/80">
          {versus ? 'Đấu tay đôi' : 'Luyện tập một mình'}
        </p>
        <h2
          id="pregame-title"
          className="text-3xl font-black text-white tracking-wider uppercase mt-0.5"
          style={{ fontFamily: DISPLAY_FONT_STACK, textShadow: '0 0 25px rgba(255,159,67,0.8)' }}
        >
          Bắt cá
        </h2>
        <p className="mt-1 text-base font-extrabold text-white/90 leading-snug break-words max-w-full" style={{ fontFamily: VI_FONT_STACK }}>
          {state.deckTitle}
        </p>

        {awaitingStart ? (
          <div className="w-full min-w-0 grid grid-cols-1 gap-3 lg:grid-cols-[minmax(0,1fr)_15rem] items-start">
            <div className="min-w-0">
              <SoloSetup state={state} deckCards={deckCards} starting={starting} skinSaving={!!skinBusy}
                assetsReady={assetsReady} assetError={assetError} onAssetRetry={onAssetRetry} onStart={onStart} />
            </div>
            <div className="min-w-0 lg:mt-4">
              <SkinPanel skin={skin} options={skinOptions} balance={skinBalance} busy={skinBusy}
                disabled={starting} error={skinError} onRetry={onSkinRetry} onChange={onSkinChange} />
            </div>
          </div>
        ) : (
          <>
            <p className="mt-2 inline-flex flex-wrap items-center justify-center gap-1 text-xs font-bold text-amber-100 bg-amber-500/20 border border-amber-400/40 rounded-full px-3 py-1">
              <span>Hỏi: {config.questionLabel}</span>
              <span aria-hidden="true">→</span>
              <span>Đáp: {config.answerLabel}</span>
            </p>
            <div className="w-full grid grid-cols-2 sm:grid-cols-4 gap-2 mt-4 text-xs">
              {[
                { label: 'Độ khó', value: difficultyLabel(config.difficulty) },
                { label: 'Thời gian', value: `${config.durationSeconds} giây` },
                { label: 'Số tim', value: `${config.lives} tim` },
                { label: 'Số cá / lượt', value: `${config.choices} con` },
              ].map(item => (
                <div key={item.label} className="p-2.5 rounded-2xl bg-white/5 border border-white/10">
                  <span className="block text-[10px] text-amber-300/70 uppercase tracking-widest">{item.label}</span>
                  <span className="font-black text-white text-sm">{item.value}</span>
                </div>
              ))}
            </div>

            {versus && me && (
              <div className="w-full flex items-stretch gap-2 mt-3">
                <PlayerChip player={me} label="Bạn" accent="rgba(251,191,36,0.8)" />
                <span className="self-center text-sm font-black text-rose-300" style={{ fontFamily: DISPLAY_FONT_STACK }}>VS</span>
                {opponent ? (
                  <PlayerChip player={opponent} label="Đối thủ" accent="rgba(125,211,252,0.8)" />
                ) : (
                  <div className="flex-1 px-3 py-2 rounded-2xl bg-white/5 border border-white/10 text-xs text-white/50 flex items-center">Đang chờ đối thủ…</div>
                )}
              </div>
            )}

            <div className="relative w-full h-36 overflow-hidden rounded-2xl border border-amber-300/30 bg-slate-950 mt-4 shadow-inner">
              <img
                src={assetUrl('/sprites/tasser_hand_drawn/bg_panoramic_composite.png')}
                alt=""
                aria-hidden="true"
                className="absolute inset-0 h-full w-full object-cover opacity-80"
              />
              <div className="absolute inset-0 bg-gradient-to-t from-slate-950/70 via-transparent to-transparent" />
              <div className="absolute inset-x-0 bottom-0 flex justify-center pointer-events-none">
                <ResultCatCanvas skinId={skin} state="idle" compact />
              </div>
              <div className="absolute left-3 top-3 rounded-lg border border-white/15 bg-slate-950/80 px-2.5 py-1.5 text-left backdrop-blur-md">
                <p className="text-[10px] font-black uppercase tracking-wider text-amber-300">{skinInfo.name}</p>
              </div>
              <div
                id="pregame-countdown"
                className="absolute right-3 top-3 flex flex-col items-center rounded-2xl border border-amber-300/50 bg-slate-950/85 px-3 py-1.5 backdrop-blur-md"
                role="timer"
                aria-live="assertive"
              >
                <span className="text-[9px] font-black uppercase tracking-widest text-amber-200/80">Bắt đầu sau</span>
                <span className="text-3xl leading-none font-black text-amber-300 tabular-nums" style={{ fontFamily: DISPLAY_FONT_STACK }}>
                  {countdown === null ? '0' : countdown}
                </span>
              </div>
            </div>
          </>
        )}

      </div>
    </div>
  );
};
