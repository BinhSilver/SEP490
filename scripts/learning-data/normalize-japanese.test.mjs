import test from "node:test";
import assert from "node:assert/strict";
import { normalizeJapanese } from "./lib/workbook-contract.mjs";

// Spec §4: katakana → hiragana(NFKC(text)), keeping ー and ・; owner 2026-10-21 (OD-39) also keeps ヽ/ヾ.
test("normalizeJapanese follows the shared normalization rule on edge characters", () => {
  assert.equal(normalizeJapanese("カタカナ"), "かたかな");
  assert.equal(normalizeJapanese("ラーメン"), "らーめん");
  assert.equal(normalizeJapanese("ジョン・スミス"), "じょん・すみす");
  assert.equal(normalizeJapanese("ヴ"), "ゔ");
  assert.equal(normalizeJapanese("ｶﾀｶﾅ"), "かたかな", "half-width folds through NFKC");
  assert.equal(normalizeJapanese("Ａ社"), "A社", "Latin is not lower-cased");
  assert.equal(normalizeJapanese("　"), " ", "U+3000 folds to a space and is not trimmed");
});

test("OD-39: the iteration marks ヽ and ヾ stay as they are", () => {
  assert.equal(normalizeJapanese("ヽ"), "ヽ");
  assert.equal(normalizeJapanese("ヾ"), "ヾ");
  assert.equal(normalizeJapanese("サヽキ"), "さヽき");
});
