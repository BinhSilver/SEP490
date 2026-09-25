import { normalizeAnswer, compareCharacters, ANSWER_TYPES } from './rules.js';
/** Input state, independent from the DOM. Server owns session transitions and scoring. */
export class RecallEngine {
  constructor() { this.view = null; this.busy = false; this.composing = false; this.input = ''; this.type = ANSWER_TYPES.HIRAGANA; }
  setView(view) {
    if (view.question?.id !== this.view?.question?.id) { this.input = ''; this.composing = false; }
    this.view = view;
  }
  canSubmit() { return !this.busy && !this.composing && this.view?.state === 'PLAYING' && normalizeAnswer(this.input) !== ''; }
  // Visual slots must never disappear during an IME composition; only submission is locked.
  slots() {
    // Free-text answers show what was typed in one box: per-character slots would give the length away
    // and do not fit answers with several accepted alternatives.
    if (this.type.free) return { free: true, text: this.type.display(this.input) };
    const expected = this.view?.question?.expectedAnswer ?? '';
    const limit = Array.from(this.type.display(expected)).length;
    return compareCharacters(this.input, expected, this.type).slice(0, limit);
  }
  matches() { return this.type.matches(this.input, this.view?.question?.expectedAnswer); }
  shouldAutoSubmit() { return this.canSubmit() && this.view.autoSubmit && this.matches(); }
}
