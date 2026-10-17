import assert from "node:assert/strict";
import test from "node:test";

import {
  buildUniqueIndex,
  compactLessonFromMaster,
  findExistingCompoundCandidate,
  hasFormulaErrors,
  resolveContainedLessonReading,
  resolveUniqueField,
} from "./repair_n5_n4_workbook.mjs";

test("resolves only one consistent master value for an exact expression", () => {
  const index = buildUniqueIndex([
    { expression: "時間", reading: "じかん" },
    { expression: "時間", reading: "じかん" },
    { expression: "行く", reading: "いく" },
    { expression: "行く", reading: "ゆく" },
  ], "expression");

  assert.equal(resolveUniqueField(index, "時間", "reading"), "じかん");
  assert.equal(resolveUniqueField(index, "行く", "reading"), null);
  assert.equal(resolveUniqueField(index, "不存在", "reading"), null);
});

test("maps master lesson sequence to compact N5 and N4 codes", () => {
  assert.equal(compactLessonFromMaster("LESSON-1"), "N5-L01");
  assert.equal(compactLessonFromMaster("LESSON-25"), "N5-L25");
  assert.equal(compactLessonFromMaster("LESSON-26"), "N4-L01");
  assert.equal(compactLessonFromMaster("LESSON-47"), "N4-L22");
  assert.equal(compactLessonFromMaster("LESSON-48"), null);
});

test("uses a same-lesson compound only when the candidate is unique and reviewed enough", () => {
  const vocab = [
    { membership_code: "V1", lesson_code: "N4-L01", expression: "時間", reading: "じかん" },
    { membership_code: "V2", lesson_code: "N4-L02", expression: "人間", reading: "にんげん" },
  ];
  assert.deepEqual(findExistingCompoundCandidate({ lesson_code: "N4-L01", character: "間" }, vocab), {
    membershipCode: "V1",
    expression: "時間",
    reading: "じかん",
    characterPosition: 2,
  });
  assert.equal(findExistingCompoundCandidate({ lesson_code: "N4-L03", character: "間" }, vocab), null);
  assert.equal(findExistingCompoundCandidate({ lesson_code: "N4-L01", character: "間" }, [
    ...vocab,
    { membership_code: "V3", lesson_code: "N4-L01", expression: "間", reading: "あいだ" },
  ]), null);
});

test("does not treat a zero-match inspection notice as a formula error", () => {
  assert.equal(hasFormulaErrors('{"kind":"notice","message":"Cell search matched 0 entries."}'), false);
  assert.equal(hasFormulaErrors('{"kind":"match","sheet":"A","address":"B2","value":"#REF!"}'), true);
});

test("resolves an annotated expression only from one same-lesson vocabulary candidate", () => {
  const lessonRows = [
    { expression: "やる", reading: "やる" },
    { expression: "鳴き声", reading: "なきごえ" },
  ];
  assert.equal(resolveContainedLessonReading("やる I", lessonRows), "やる");
  assert.equal(resolveContainedLessonReading("なきごえ 鳴き声", lessonRows), "なきごえ");
  assert.equal(resolveContainedLessonReading("する III", [
    { expression: "する", reading: "する" },
    { expression: "連絡する", reading: "れんらくする" },
  ]), null);
});
