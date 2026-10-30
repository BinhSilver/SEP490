import { normalizeAnswer, compareCharacters, ANSWER_TYPES } from './rules.js';
/** Input state, independent from the DOM. Server owns session transitions and scoring. */
export class RecallEngine {
  constructor() { this.view = null; this.busy = false; this.composing = false; this.input = ''; this.rawInput = ''; this.confirmedInput = ''; this.type = ANSWER_TYPES.HIRAGANA; }
  setView(view) {
    if (view.question?.id !== this.view?.question?.id) { this.input = ''; this.rawInput = ''; this.confirmedInput = ''; this.composing = false; }
    this.view = view;
  }
  // Some keyboards emit an input before compositionstart. Latin fragments still belong to
  // the pending Japanese conversion, never to the per-character answer slots.
  pendingInput() { return this.composing || (this.type.lang === 'ja' && /[a-z]/i.test(normalizeAnswer(this.input))); }
  updateInput(value, rawValue = value) {
    this.rawInput = rawValue;
    this.input = value;
    if (!this.pendingInput()) this.confirmedInput = value;
    else if (!this.composing && this.type === ANSWER_TYPES.HIRAGANA) {
      this.confirmedInput = value.match(/^[^a-z']*/i)?.[0] || '';
    }
  }
  canSubmit() { return !this.busy && !this.pendingInput() && this.view?.state === 'PLAYING' && normalizeAnswer(this.input) !== ''; }
  slots() {
    const pending = this.type.lang === 'ja' && this.pendingInput();
    const visible = pending ? this.confirmedInput : this.input;
    // Kanji and Vietnamese are free text; kana keeps one slot per confirmed character.
    if (this.type.free) return { free: true, text: this.type.display(visible), pending: pending ? this.input : '' };
    const expected = this.view?.question?.expectedAnswer ?? '';
    const limit = Array.from(this.type.display(expected)).length;
    const values = compareCharacters(visible, expected, this.type).slice(0, limit);
    values.pending = pending ? this.input : '';
    return values;
  }
  matches() { return !this.pendingInput() && this.type.matches(this.input, this.view?.question?.expectedAnswer); }
  shouldAutoSubmit() { return this.canSubmit() && this.view.autoSubmit && this.matches(); }
}
