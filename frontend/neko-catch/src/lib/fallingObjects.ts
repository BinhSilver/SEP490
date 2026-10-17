import type { FallingObject } from '../types/game';

export { cardSpeedMultiplier as fallSpeedMultiplier } from '../../../shared/cardSpeed';

interface FallingStep {
  frames: number;
  frozen: boolean;
  basketX: number;
  basketY: number;
  catchRadius: number;
  missY: number;
  roundId: string | null;
  locked: boolean;
}

/** Advance the live objects in place; round outcomes are still judged by the server. */
export function stepFallingObjects(objects: FallingObject[], step: FallingStep) {
  let caught: FallingObject | null = null;
  const bonuses: FallingObject[] = [];
  let answersChanged = false;
  const hadAnswers = objects.some(item => !item.isBonus && item.roundId === step.roundId);

  for (let i = objects.length - 1; i >= 0; i--) {
    const item = objects[i];
    if (!step.frozen) {
      item.y += item.speed * step.frames;
      item.rotation += item.rotSpeed * step.frames;
    }
    const hit = Math.hypot(item.x - step.basketX, item.y - step.basketY)
      < step.catchRadius + item.width * 0.35;

    if (item.isBonus) {
      if (hit) {
        objects.splice(i, 1);
        bonuses.push(item);
      } else if (item.y > step.missY) {
        objects.splice(i, 1);
      }
    } else if (hit && !step.locked && !caught && item.roundId === step.roundId) {
      objects.splice(i, 1);
      answersChanged = true;
      caught = item;
    } else if (item.y > step.missY) {
      objects.splice(i, 1);
      answersChanged = true;
    }
  }

  // The client does not know which answer is correct. A distractor reaching the
  // ground must not end the round while another answer can still be caught.
  // If a catch is pending, exhaustion is deferred until its server reply: a
  // successful catch advances the round; a shielded wrong catch may still miss.
  const exhausted = hadAnswers && !objects.some(item => !item.isBonus && item.roundId === step.roundId);
  return { caught, bonuses, answersChanged, exhausted };
}
