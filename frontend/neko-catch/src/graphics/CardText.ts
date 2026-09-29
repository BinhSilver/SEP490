import { JP_FONT_STACK, VI_FONT_STACK } from '../lib/text';

/**
 * Fits an answer onto a falling fish card: the largest font size (MAX → MIN) that fits on one line,
 * otherwise on two balanced lines, otherwise two lines at the minimum size with an ellipsis.
 * Vietnamese breaks between words; Japanese between characters (never before small kana / closing marks).
 */
export interface CardTextLayout {
  font: string;
  lines: string[];
  lineHeight: number;
}

const MAX_SIZE = 20;
const MIN_SIZE = 10;
const LINE_HEIGHT = 1.12;

/** Characters that must not start a line in Japanese (simple kinsoku shori). */
const NO_LINE_START = new Set(Array.from('、。，．・：；？！ー～）」』】〕〉》’”ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ)]}.,!?;:%'));
/** Characters that must not end a line in Japanese. */
const NO_LINE_END = new Set(Array.from('（「『【〔〈《‘“([{'));

let fontsVersion = 0;
const cache = new Map<string, CardTextLayout>();

if (typeof document !== 'undefined' && document.fonts) {
  // Web fonts finish loading after the first frames: re-measure with the real metrics once they arrive.
  const bump = () => {
    fontsVersion++;
    cache.clear();
  };
  document.fonts.addEventListener?.('loadingdone', bump);
  document.fonts.ready.then(bump).catch(() => {});
}

function fontFor(size: number, japanese: boolean): string {
  return japanese ? `800 ${size}px ${JP_FONT_STACK}` : `800 ${size}px ${VI_FONT_STACK}`;
}

function breakCandidates(text: string, japanese: boolean): number[] {
  const chars = Array.from(text);
  const positions: number[] = [];
  let offset = 0;
  for (let i = 0; i < chars.length; i++) {
    offset += chars[i].length;
    if (i === chars.length - 1) break;
    const next = chars[i + 1];
    if (japanese && !/\s/.test(chars[i]) && !/\s/.test(next)) {
      if (NO_LINE_START.has(next) || NO_LINE_END.has(chars[i])) continue;
      positions.push(offset);
    } else if (/\s/.test(next)) {
      positions.push(offset);
    }
  }
  return positions;
}

function charBreaks(text: string): number[] {
  const chars = Array.from(text);
  const positions: number[] = [];
  let offset = 0;
  for (let i = 0; i < chars.length - 1; i++) {
    offset += chars[i].length;
    positions.push(offset);
  }
  return positions;
}

function bestTwoLineSplit(ctx: CanvasRenderingContext2D, text: string, maxWidth: number, japanese: boolean): string[] | null {
  let candidates = breakCandidates(text, japanese);
  if (candidates.length === 0) candidates = charBreaks(text);
  let best: string[] | null = null;
  let bestWidth = Infinity;
  for (const position of candidates) {
    const first = text.slice(0, position).trim();
    const second = text.slice(position).trim();
    if (!first || !second) continue;
    const widest = Math.max(ctx.measureText(first).width, ctx.measureText(second).width);
    if (widest <= maxWidth && widest < bestWidth) {
      best = [first, second];
      bestWidth = widest;
    }
  }
  return best;
}

function truncate(ctx: CanvasRenderingContext2D, text: string, maxWidth: number): string {
  if (ctx.measureText(text).width <= maxWidth) return text;
  const chars = Array.from(text);
  while (chars.length > 1 && ctx.measureText(chars.join('').trimEnd() + '…').width > maxWidth) {
    chars.pop();
  }
  return chars.join('').trimEnd() + '…';
}

function greedyTwoLines(ctx: CanvasRenderingContext2D, text: string, maxWidth: number, japanese: boolean): string[] {
  const chars = Array.from(text);
  let first = '';
  let consumed = 0;
  const candidates = new Set(breakCandidates(text, japanese));
  let lastBreak = -1;
  let offset = 0;
  for (let i = 0; i < chars.length; i++) {
    const trial = text.slice(0, offset + chars[i].length);
    if (ctx.measureText(trial.trim()).width > maxWidth) break;
    offset += chars[i].length;
    if (candidates.has(offset)) lastBreak = offset;
    consumed = offset;
  }
  const minCut = chars[0]?.length ?? 1;
  const cut = Math.max(minCut, lastBreak > 0 && !japanese ? lastBreak : consumed);
  first = text.slice(0, cut).trim();
  const rest = text.slice(cut).trim();
  return rest ? [first, truncate(ctx, rest, maxWidth)] : [truncate(ctx, first, maxWidth)];
}

export function layoutCardText(
  ctx: CanvasRenderingContext2D,
  rawText: string,
  maxWidth: number,
  maxHeight: number,
  japanese: boolean
): CardTextLayout {
  const text = rawText.replace(/\s+/g, ' ').trim();
  const key = `${fontsVersion}|${japanese ? 'jp' : 'vi'}|${Math.round(maxWidth)}|${Math.round(maxHeight)}|${text}`;
  const cached = cache.get(key);
  if (cached) return cached;

  ctx.save();
  let layout: CardTextLayout | null = null;
  for (let size = MAX_SIZE; size >= MIN_SIZE && !layout; size--) {
    const font = fontFor(size, japanese);
    ctx.font = font;
    const lineHeight = Math.round(size * LINE_HEIGHT);
    if (ctx.measureText(text).width <= maxWidth) {
      layout = { font, lines: [text], lineHeight };
    } else if (lineHeight * 2 <= maxHeight) {
      const lines = bestTwoLineSplit(ctx, text, maxWidth, japanese);
      if (lines) layout = { font, lines, lineHeight };
    }
  }
  if (!layout) {
    const font = fontFor(MIN_SIZE, japanese);
    ctx.font = font;
    layout = { font, lines: greedyTwoLines(ctx, text, maxWidth, japanese), lineHeight: Math.round(MIN_SIZE * LINE_HEIGHT) };
  }
  ctx.restore();

  if (cache.size > 400) cache.clear();
  cache.set(key, layout);
  return layout;
}
