import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { validateRequiredRows, validateWorkbook } from "./validate-learning-workbook.mjs";
import { generateSeed, resolveCorrectOption } from "./generate-learning-seed.mjs";
import { escapeSqlString } from "./lib/sql-writer.mjs";

const fixture = (name) => path.join(import.meta.dirname, "fixtures", name);

test("rejects orphan lesson and unresolved canonical conflict", async () => {
  const issues = await validateWorkbook(fixture("invalid-orphans.xlsx"));
  assert.deepEqual(issues.map((issue) => issue.code), [
    "UNKNOWN_UNIT_CODE",
    "CANONICAL_CONFLICT",
  ]);
});

test("rejects kanji without a compound vocabulary in the same lesson", async () => {
  const issues = await validateWorkbook(fixture("invalid-kanji.xlsx"));
  assert.ok(issues.some((issue) => issue.code === "KANJI_WITHOUT_LESSON_VOCAB"));
});

test("does not modify SQL outputs when validation fails", async () => {
  const outputDir = await fs.mkdtemp(path.join(os.tmpdir(), "jlas-invalid-seed-"));
  const existing = path.join(outputDir, "seed_learning_path_catalog.sql");
  await fs.writeFile(existing, "keep-me", "utf8");

  await assert.rejects(
    () => generateSeed(fixture("invalid-orphans.xlsx"), outputDir),
    /Workbook validation failed/,
  );
  assert.equal(await fs.readFile(existing, "utf8"), "keep-me");
  assert.deepEqual((await fs.readdir(outputDir)).sort(), ["seed_learning_path_catalog.sql"]);
});

test("escapes apostrophes without changing Japanese text or newlines", () => {
  assert.equal(escapeSqlString("先生's\n日本語"), "先生''s\n日本語");
});

test("reports a populated row whose stable code is blank", () => {
  const issues = validateRequiredRows({
    LP_UNIT: {
      rows: [{ __row: 5, unit_code: "", textbook_code: "LP-N5", title: "Unit", order_index: 1, source_book_code: "N5-CORE" }],
    },
  }, { LP_UNIT: ["unit_code", "textbook_code", "title", "order_index", "source_book_code"] });
  assert.deepEqual(issues.map((item) => item.code), ["MISSING_REQUIRED_VALUE"]);
});

test("rejects sentinel values that disguise missing required learning data", () => {
  const issues = validateRequiredRows({
    LESSON_GRAMMAR: {
      rows: [{
        __row: 5,
        grammar_code: "GRAMMAR-undefined",
        lesson_code: "N5-L01",
        source_book_code: "N5-CORE",
        source_lesson_ref: "Bài 1",
        pattern: "-",
        meaning_vi: "placeholder",
        order_index: 1,
        canonical_lookup_status: "NOT_FOUND",
      }],
    },
  }, {
    LESSON_GRAMMAR: [
      "grammar_code", "lesson_code", "source_book_code", "source_lesson_ref",
      "pattern", "meaning_vi", "order_index", "canonical_lookup_status",
    ],
  });

  assert.deepEqual(issues.map((item) => [item.code, item.message]), [
    ["SENTINEL_VALUE_NOT_ALLOWED", "Sentinel value is not allowed for grammar_code"],
    ["SENTINEL_VALUE_NOT_ALLOWED", "Sentinel value is not allowed for pattern"],
    ["SENTINEL_VALUE_NOT_ALLOWED", "Sentinel value is not allowed for meaning_vi"],
  ]);
});

test("resolves TRUE_FALSE and text answers to inline option indexes", () => {
  assert.equal(resolveCorrectOption({ correct_answer: "Đúng", option_a: "Đúng", option_b: "Sai" }), 1);
  assert.equal(resolveCorrectOption({ correct_answer: "本", option_a: "時間", option_b: "本" }), 2);
});

test("generates four dependency-ordered, idempotent SQL files", async () => {
  const outputDir = await fs.mkdtemp(path.join(os.tmpdir(), "jlas-valid-seed-"));
  const manifest = await generateSeed(fixture("valid-minimal.xlsx"), outputDir);

  assert.equal(manifest.lessonCount, 2);
  assert.equal(manifest.files.length, 4);
  assert.deepEqual((await fs.readdir(outputDir)).sort(), [
    "seed_learning_path_catalog.sql",
    "seed_learning_path_exercises.sql",
    "seed_learning_path_n4.sql",
    "seed_learning_path_n5.sql",
  ]);

  const catalog = await fs.readFile(path.join(outputDir, "seed_learning_path_catalog.sql"), "utf8");
  const n4 = await fs.readFile(path.join(outputDir, "seed_learning_path_n4.sql"), "utf8");
  assert.match(catalog, /INSERT INTO textbook[\s\S]+ON DUPLICATE KEY UPDATE/);
  assert.match(catalog, /SET @book_/);
  assert.match(n4, /SET @lesson_lp_n4_l01 = \(SELECT (?:l\.)?id FROM lesson/);
  assert.match(n4, /SET @section_lp_n4_l01 = \(SELECT id FROM lesson_section/);
  assert.match(n4, /INSERT INTO vocabulary_kanji[\s\S]+ON DUPLICATE KEY UPDATE/);
  assert.doesNotMatch(`${catalog}\n${n4}`, /learning_activity_member/i);
});
