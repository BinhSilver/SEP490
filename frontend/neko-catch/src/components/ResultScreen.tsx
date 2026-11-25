import React, { useRef } from 'react';
import type { DeckField, NekoMistakeView, NekoPlayerView, PlayMode, ResultSummary } from '../arena/types';
import type { CatSkinId } from '../graphics/CatSpriteSheet';
import { useFocusTrap } from '../hooks/useFocusTrap';
import { DISPLAY_FONT_STACK, fieldFont, isJapaneseField, VI_FONT_STACK } from '../lib/text';
import { CowboyVictoryScene } from './CowboyVictoryScene';
import { RotateCcwIcon as RotateCcw, TrophyIcon as Trophy } from './Icons';
import { ResultCatCanvas } from './ResultCatCanvas';

interface ResultScreenProps {
  summary: ResultSummary;
  mode: PlayMode;
  meId: number | null;
  me: NekoPlayerView | null;
  opponent: NekoPlayerView | null;
  mistakes: NekoMistakeView[];
  deckTitle: string;
  questionField: DeckField;
  answerField: DeckField;
  skin: CatSkinId;
  backUrl: string;
  onReplay: () => void;
  replaying: boolean;
  replayError: string | null;
}

/** Match result from the server summary (SESSION_FINISHED or state.result on reload) plus the player's mistakes. */
export const ResultScreen: React.FC<ResultScreenProps> = ({
  summary,
  mode,
  meId,
  me,
  opponent,
  mistakes,
  deckTitle,
  questionField,
  answerField,
  skin,
  backUrl,
  onReplay,
  replaying,
  replayError,
}) => {
  const ref = useRef<HTMLDivElement | null>(null);
  useFocusTrap(ref, true);

  const versus = mode === 'VERSUS';
  const won = summary.winnerUserId !== null && summary.winnerUserId === meId;
  const perfect = won && !!me && me.wrong === 0 && me.correct > 0;
  const questionFont = fieldFont(questionField);
  const answerFont = fieldFont(answerField);
  const questionLang = isJapaneseField(questionField) ? 'ja' : 'vi';
  const answerLang = isJapaneseField(answerField) ? 'ja' : 'vi';

  let headline: string;
  let subline: string;
  if (summary.draw) {
    headline = 'Hoà!';
    subline = 'Hai bên bằng điểm, bằng số câu đúng và số câu sai.';
  } else if (won) {
    headline = 'Bạn thắng!';
    subline = versus
      ? opponent?.finishReason === 'FORFEIT'
        ? 'Đối thủ đã rời trận.'
        : 'Bạn giành chiến thắng trong trận đối kháng!'
      : perfect
        ? 'Xuất sắc 100%! Bạn đã hứng đúng toàn bộ các từ vựng!'
        : 'Chiến thắng rực rỡ! Bội thu từ vựng và điểm thưởng!';
  } else if (versus) {
    headline = 'Đối thủ thắng';
    subline = me?.finishReason === 'FORFEIT' ? 'Bạn đã rời trận.' : 'Đừng nản lòng, luyện tập nhiều sẽ thành công!';
  } else {
    headline = 'Thử lại nhé!';
    subline =
      me?.finishReason === 'LIVES'
        ? 'Hết tim trước khi hết giờ. Đừng nản lòng, luyện tập nhiều sẽ thành công!'
        : 'Đừng nản lòng, luyện tập nhiều sẽ thành công!';
  }

  const celebrate = won || summary.draw;

  return (
    <div className="absolute inset-0 z-30 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-fade-in">
      <div
        ref={ref}
        role="dialog"
        data-game-ui-panel
        aria-modal="true"
        aria-labelledby="result-title"
        aria-describedby="result-subtitle"
        className="relative flex flex-col items-center text-center p-5 sm:p-6 rounded-3xl max-w-lg w-full max-h-[calc(100dvh-2rem)] overflow-y-auto custom-scrollbar border-2 border-amber-400/40 shadow-2xl"
        style={{
          background: 'linear-gradient(160deg, rgba(28, 14, 62, 0.98), rgba(14, 5, 36, 0.99))',
          boxShadow: celebrate ? '0 0 70px rgba(255, 159, 67, 0.35)' : '0 0 50px rgba(225, 29, 72, 0.25)',
        }}
      >
        {/* 1. Character animation (the Cowboy cat keeps its own victory scene) */}
        <div className="w-full min-h-[176px] rounded-2xl border border-white/10 bg-slate-950/65 px-3 py-2 flex items-center justify-center overflow-hidden">
          {won && skin === 1 ? <CowboyVictoryScene /> : <ResultCatCanvas skinId={skin} state={celebrate ? 'win' : 'lose'} />}
        </div>

        {/* 2. Headline */}
        <p className="mt-3 text-[10px] font-black uppercase tracking-[0.2em] text-amber-300/70">
          {versus ? 'Kết quả đối kháng' : 'Kết quả luyện tập'} · <span style={{ fontFamily: VI_FONT_STACK }}>{deckTitle}</span>
        </p>
        <h2
          id="result-title"
          className="text-2xl sm:text-3xl font-black text-white uppercase tracking-widest mt-1 mb-0.5"
          style={{ fontFamily: DISPLAY_FONT_STACK }}
        >
          {headline}
        </h2>
        <p id="result-subtitle" className="text-xs font-semibold text-amber-300/85 mb-4">
          {subline}
        </p>

        {/* 3. My numbers, straight from the server */}
        {me && (
          <div className="grid grid-cols-3 gap-2 w-full mb-4">
            <div className="p-2.5 rounded-2xl bg-white/5 border border-white/10 flex flex-col items-center justify-center">
              <span className="block text-[9px] font-bold text-amber-300/70 uppercase mb-1">Tổng điểm</span>
              <span className="text-xl font-black text-amber-400 tabular-nums" style={{ fontFamily: DISPLAY_FONT_STACK }}>
                {me.score}
              </span>
            </div>
            <div className="p-2.5 rounded-2xl bg-white/5 border border-white/10 flex flex-col items-center justify-center">
              <span className="block text-[9px] font-bold text-slate-300/70 uppercase mb-1">Kết quả</span>
              <div className="flex flex-wrap items-center justify-center gap-1 font-black text-[11px]">
                <span className="text-emerald-400 bg-emerald-500/15 px-1.5 py-0.5 rounded-md border border-emerald-500/30">
                  ĐÚNG {me.correct}
                </span>
                <span className="text-rose-400 bg-rose-500/15 px-1.5 py-0.5 rounded-md border border-rose-500/30">
                  SAI {me.wrong}
                </span>
              </div>
            </div>
            <div className="p-2.5 rounded-2xl bg-white/5 border border-white/10 flex flex-col items-center justify-center">
              <span className="block text-[9px] font-bold text-rose-300/70 uppercase mb-1">Max combo</span>
              <span className="text-xl font-black text-rose-400 tabular-nums" style={{ fontFamily: DISPLAY_FONT_STACK }}>
                ×{me.maxCombo}
              </span>
            </div>
          </div>
        )}

        {/* 4. Every result line of the summary */}
        <div className="w-full mb-4 text-left">
          <p className="text-[10px] font-bold text-amber-300/80 uppercase tracking-widest px-1 mb-1.5">Bảng kết quả</p>
          <ol className="space-y-1.5">
            {summary.lines.map(line => {
              const mine = line.userId === meId;
              return (
                <li
                  key={line.userId}
                  className={`flex items-center gap-3 px-3 py-2 rounded-xl border ${
                    mine ? 'bg-amber-500/10 border-amber-400/40' : 'bg-slate-900/80 border-white/5'
                  }`}
                >
                  <span
                    className="w-7 h-7 shrink-0 rounded-lg bg-white/10 flex items-center justify-center text-xs font-black text-white"
                    aria-label={`Hạng ${line.rank}`}
                  >
                    {line.rank}
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="flex items-center gap-1.5 min-w-0 text-sm font-bold text-white">
                      <span className="truncate" title={line.displayName}>{line.displayName}</span>
                      {mine && (
                        <span className="shrink-0 text-[9px] font-black uppercase tracking-wider text-amber-300 bg-amber-400/15 border border-amber-400/30 rounded px-1">
                          Bạn
                        </span>
                      )}
                      {line.winner && (
                        <span className="shrink-0 text-amber-300" aria-label="Người thắng">
                          <Trophy className="w-4 h-4" />
                        </span>
                      )}
                    </p>
                    <p className="text-[11px] text-white/60 break-words">{line.detail}</p>
                  </div>
                  <span className="shrink-0 text-lg font-black text-amber-300 tabular-nums" style={{ fontFamily: DISPLAY_FONT_STACK }}>
                    {line.score}
                  </span>
                </li>
              );
            })}
          </ol>
        </div>

        {/* 5. Words to review */}
        {mistakes.length > 0 ? (
          <div className="w-full mb-5 text-left">
            <p className="text-[10px] font-bold text-amber-300/80 uppercase tracking-widest px-1 mb-1.5">
              Từ cần ôn lại ({mistakes.length} từ)
            </p>
            <ul className="max-h-40 overflow-y-auto space-y-1.5 pr-1 custom-scrollbar">
              {mistakes.map((mistake, index) => (
                <li
                  key={`${index}-${mistake.prompt}`}
                  className="px-3 py-2 rounded-xl bg-slate-900/80 border-l-4 border-rose-500/80 border border-white/5 shadow-sm"
                >
                  <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 min-w-0">
                    <span className="font-bold text-amber-300 text-sm break-words" style={{ fontFamily: questionFont }} lang={questionLang}>
                      {mistake.prompt}
                    </span>
                    <span className="min-w-0 text-sm text-slate-200 break-words">
                      → <span style={{ fontFamily: answerFont }} lang={answerLang}>{mistake.correctText}</span>
                    </span>
                  </div>
                  <p className="text-[11px] text-rose-300/90 mt-0.5 break-words">
                    {mistake.chosenText ? (
                      <>
                        Bạn chọn: <span style={{ fontFamily: answerFont }} lang={answerLang}>{mistake.chosenText}</span>
                      </>
                    ) : (
                      'Để lỡ cá đúng'
                    )}
                  </p>
                </li>
              ))}
            </ul>
          </div>
        ) : (
          <div className="w-full mb-4 py-2 px-3 rounded-2xl bg-emerald-500/10 border border-emerald-500/20 text-center">
            <span className="text-xs font-bold text-emerald-300">Không có từ nào bị sai. Tuyệt vời!</span>
          </div>
        )}

        {replayError && (
          <p className="w-full mb-3 text-xs font-semibold text-rose-200 bg-rose-500/15 border border-rose-400/40 rounded-lg px-3 py-2" role="alert">
            {replayError}
          </p>
        )}

        {/* 6. Actions */}
        <div className="w-full flex flex-col sm:flex-row gap-2">
          {versus ? (
            <a
              href={backUrl}
              data-autofocus
              className="flex-1 py-3 min-h-[44px] rounded-2xl font-black text-white flex items-center justify-center gap-2 uppercase tracking-widest text-xs transition-all hover:scale-[1.02] shadow-lg no-underline focus-visible:ring-2 focus-visible:ring-amber-300"
              style={{ fontFamily: DISPLAY_FONT_STACK, background: 'linear-gradient(135deg, #FF9F43, #FF6B35)', boxShadow: '0 0 20px rgba(255,159,67,0.35)' }}
            >
              Về phòng
            </a>
          ) : (
            <>
              <button
                type="button"
                data-autofocus
                onClick={onReplay}
                data-game-ui-primary
                disabled={replaying}
                className="flex-1 py-3 min-h-[44px] rounded-2xl font-black text-white flex items-center justify-center gap-2 uppercase tracking-widest text-xs transition-all hover:scale-[1.02] shadow-lg disabled:opacity-60 disabled:hover:scale-100 focus-visible:ring-2 focus-visible:ring-amber-300"
                style={{ fontFamily: DISPLAY_FONT_STACK, background: 'linear-gradient(135deg, #FF9F43, #FF6B35)', boxShadow: '0 0 20px rgba(255,159,67,0.35)' }}
              >
                <RotateCcw className="w-4 h-4" />
                {replaying ? 'Đang tạo trận…' : 'Chơi lại'}
              </button>
              <a
                href="/games"
                className="flex-1 py-3 min-h-[44px] rounded-2xl font-bold text-white/85 flex items-center justify-center uppercase tracking-widest text-xs bg-white/10 border border-white/15 hover:bg-white/20 transition-all no-underline focus-visible:ring-2 focus-visible:ring-amber-300"
                style={{ fontFamily: DISPLAY_FONT_STACK }}
              >
                Về trang Trò chơi
              </a>
            </>
          )}
        </div>
      </div>
    </div>
  );
};
