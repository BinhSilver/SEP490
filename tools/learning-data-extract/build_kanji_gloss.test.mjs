import test from "node:test";
import assert from "node:assert/strict";
import { buildKanjiGloss } from "./build_kanji_gloss.mjs";

const section = {
  owned_root_code: "LP-OWNED-KANJI-N5-N1",
  content: {
    license: { source: "KANJIDIC2", publisher: "EDRDG", license: "CC BY-SA 4.0", url: "https://example.test/kanjidic",
      level_source: "Waller via OpenJLPT" },
    kanji: [
      { character: "一", jlpt_level: "N5", han_viet: "Nhất", meanings_en: ["one", "one radical (no.1)", "x", "y"] },
      { character: "妖", jlpt_level: null, han_viet: "Yêu", meanings_en: ["attractive"] },
      { character: "二", jlpt_level: "N5", han_viet: "Nhị", meanings_en: [] }
    ]
  }
};

test("OD-13: keeps JLPT-levelled Kanji with English meanings, at most three, and carries the licence", () => {
  const gloss = buildKanjiGloss(section, "abc123");
  assert.deepEqual(Object.keys(gloss.kanji), ["一"], "no level or no meaning is left out");
  assert.deepEqual(gloss.kanji["一"], ["one", "one radical (no.1)", "x"]);
  assert.equal(gloss.attribution.license, "CC BY-SA 4.0");
  assert.equal(gloss.attribution.levelStatus, "NOT_VERIFIED");
  assert.equal(gloss.sourceSha256, "abc123");
});
