export const MOBILE_GAME_MAX_WIDTH = 900;
export const MOBILE_MAX_FISH = 5;

/** Canvas coordinates stay in CSS pixels; artwork and catches share the same scale. */
export function playfieldSize(width: number, height: number) {
  const mobile = width <= MOBILE_GAME_MAX_WIDTH;
  const scale = mobile ? Math.max(0.55, Math.min(0.7, width / 650)) : 1;
  return {
    mobile,
    scale,
    catWidth: 88 * scale,
    catHeight: 96 * scale,
    catY: mobile ? height - 96 * scale * 0.42 - 18 : height - 120,
    edge: 60 * scale,
    catchRadius: 56 * scale,
    basketOffset: 22 * scale,
    missY: height - (mobile ? 8 : 90),
    bonusSize: 72 * scale,
  };
}

export function answerFishSize(width: number, choiceCount: number) {
  const spacing = width / (Math.max(1, choiceCount) + 1);
  const fishWidth = Math.round(width <= MOBILE_GAME_MAX_WIDTH
    ? Math.max(68, Math.min(92, spacing * 1.25)) * 0.9
    : Math.max(96, Math.min(140, spacing * 1.7)));
  return { width: fishWidth, height: Math.round(fishWidth * 64 / 140) };
}

/** Cover the viewport without distorting the artwork; crop equally from both sides. */
export function backgroundCover(imageWidth: number, imageHeight: number, width: number, height: number) {
  const scale = Math.max(width / imageWidth, height / imageHeight);
  const drawWidth = imageWidth * scale;
  const drawHeight = imageHeight * scale;
  return { x: (width - drawWidth) / 2, y: (height - drawHeight) / 2, width: drawWidth, height: drawHeight };
}
