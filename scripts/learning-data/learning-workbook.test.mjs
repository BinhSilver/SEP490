import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { validateWorkbook } from "./validate-learning-workbook.mjs";
import { generateSeed } from "./generate-learning-seed.mjs";
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
