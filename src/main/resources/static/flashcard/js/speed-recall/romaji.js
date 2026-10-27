import { toHiragana } from '../vendor/wanakana-5.3.1.js';

/** Keep the raw spelling in the input so editing never loses an ambiguous n/nn. */
export function romajiToHiragana(value, { commit = false } = {}) {
  const text = String(value ?? '');
  if (!/[a-z]/i.test(text.normalize('NFKC'))) return text;
  let raw = text.normalize('NFKC').toLowerCase();
  // Accept nn as a committed nasal before consonants or at the end, while nna/nni
  // retain the second n as the next syllable (e.g. konnichiwa).
  raw = raw.replace(/nn(?=[^aiueoyn]|$)/g, 'n');
  if (raw.endsWith('ny')) return toHiragana(raw.slice(0, -2)) + 'ny';
  if (!commit && raw.endsWith('n')) {
    return toHiragana(raw.slice(0, -1)) + 'n';
  }
  return toHiragana(raw);
}
