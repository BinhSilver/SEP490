import { normalizeAnswer, compareCharacters } from './rules.js';
/** Input state, independent from the DOM. Server owns session transitions and scoring. */
export class RecallEngine {
  constructor() { this.view = null; this.busy = false; this.composing = false; this.input = ''; }
  setView(view) {
    if (view.question?.id !== this.view?.question?.id) { this.input = ''; this.composing = false; }
    this.view = view;
  }
  canSubmit() { return !this.busy && !this.composing && this.view?.state === 'PLAYING' && normalizeAnswer(this.input) !== ''; }
  // Visual slots must never disappear during an IME composition; only submission is locked.
  slots() {
    const expected = this.view?.question?.expectedAnswer ?? '';
    const limit = Array.from(normalizeAnswer(expected)).length;
    return compareCharacters(this.input, expected).slice(0, limit);
  }
  shouldAutoSubmit() {
    return this.canSubmit() && this.view.autoSubmit && normalizeAnswer(this.input) === this.view.question.expectedAnswer;
  }
}
