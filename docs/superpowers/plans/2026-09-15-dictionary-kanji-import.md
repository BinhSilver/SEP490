# Dictionary and Kanji Base Data Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an operator-invoked, idempotent Python pipeline that imports JMdict, KANJIDIC2, and KanjiVG into the existing MySQL dictionary schema with auditable batch results.

**Architecture:** Source-specific streaming parsers emit typed records consumed by a shared pipeline. A MySQL store owns prepared SQL, savepoints, chunk commits, catalog caches, merge rules, and batch state; parsers and normalization remain database-independent and unit-testable.

**Tech Stack:** Python 3.11+, lxml, mysql-connector-python, pykakasi, unittest, MySQL 8.0.16+, Java 21/Maven baseline build.

**Spec:** `docs/superpowers/specs/2026-09-15-dictionary-kanji-import-design.md`

## Global Constraints

- Add exactly `dictionary_source`, `dictionary_import_batch`, and `vocabulary_external_reference`; do not add other tables.
- Preserve all existing Dictionary/Kanji tables and Java search behavior.
- Run only from an explicit Python command; never from Spring Boot startup, a request, or a scheduler.
- Parse large XML inputs incrementally and keep foreign-key validation enabled.
- Deduplicate vocabulary by normalized expression/reading and JMdict provenance by source/external ID.
- Preserve textbook meaning, word type, examples, JLPT level, and content links.
- Never log database credentials, secrets, or stack traces in persistent batch fields.

---

### Task 1: Import Metadata Schema

**Files:**
- Modify: `jlas_schema.sql`
- Create: `sql/migrations/004_dictionary_import_metadata.sql`
- Create: `scripts/dictionary_import/tests/test_schema_contract.py`

**Interfaces:**
- Consumes: the existing `vocabulary_entry(id)` table.
- Produces: the three tables and source rows required by the store in Task 5.

- [ ] **Step 1: Write the failing schema contract test**

```python
class SchemaContractTest(unittest.TestCase):
    def test_import_tables_have_required_integrity_constraints(self):
        schema = Path(REPO_ROOT, "jlas_schema.sql").read_text(encoding="utf-8")
        self.assertIn("CREATE TABLE dictionary_source", schema)
        self.assertIn("CREATE TABLE dictionary_import_batch", schema)
        self.assertIn("CREATE TABLE vocabulary_external_reference", schema)
        self.assertIn("UNIQUE KEY uk_vocabulary_external_source", schema)
        self.assertIn("CONSTRAINT chk_dictionary_import_status", schema)
        self.assertIn("CONSTRAINT chk_dictionary_import_counters", schema)
```

- [ ] **Step 2: Run the test and verify RED**

Run: `python -m unittest scripts.dictionary_import.tests.test_schema_contract -v`

Expected: FAIL because none of the three tables exist.

- [ ] **Step 3: Add the canonical schema and additive migration**

Implement matching `CREATE TABLE` statements with MySQL 8 types, named foreign
keys/checks/indexes, SHA-256 `CHAR(64)`, and non-negative counters. Seed
JMDICT/KANJIDIC2/KANJIVG using idempotent `INSERT ... ON DUPLICATE KEY UPDATE` in
the migration and ordinary seed inserts in the canonical empty-database schema.

- [ ] **Step 4: Run the schema contract test and verify GREEN**

Run: `python -m unittest scripts.dictionary_import.tests.test_schema_contract -v`

Expected: PASS.

- [ ] **Step 5: Commit**

```text
git add jlas_schema.sql sql/migrations/004_dictionary_import_metadata.sql scripts/dictionary_import/tests/test_schema_contract.py
git commit -m "feat: add dictionary import metadata schema"
```

### Task 2: Shared Models and Normalization

**Files:**
- Create: `scripts/dictionary_import/jlas_import/__init__.py`
- Create: `scripts/dictionary_import/jlas_import/models.py`
- Create: `scripts/dictionary_import/jlas_import/normalization.py`
- Create: `scripts/dictionary_import/tests/test_normalization.py`

**Interfaces:**
- Produces: `normalize_text(value) -> str`, `normalize_reading(value) -> str`,
  `reading_to_romaji(value) -> str`, `is_kanji_codepoint(value) -> bool`, and
  dataclasses `VocabularyRecord`, `VocabularySenseRecord`, `KanjiRecord`,
  `KanjiReadingRecord`, and `KanjiSvgRecord`.

- [ ] **Step 1: Write failing normalization tests**

```python
class NormalizationTest(unittest.TestCase):
    def test_nfkc_trim_and_whitespace_collapse(self):
        self.assertEqual("ABC 日本", normalize_text("  ＡＢＣ\t 日本  "))

    def test_reading_key_converts_katakana_to_hiragana(self):
        self.assertEqual("がっこう", normalize_reading(" ガッコウ "))

    def test_romaji_uses_hepburn(self):
        self.assertEqual("gakkou", reading_to_romaji("がっこう"))
```

- [ ] **Step 2: Run and verify RED**

Run: `python -m unittest scripts.dictionary_import.tests.test_normalization -v`

Expected: import failure because the normalization module does not exist.

- [ ] **Step 3: Implement immutable source records and normalization**

Use `unicodedata.normalize("NFKC", value)`, a whitespace regex, explicit
Katakana code-point conversion, and one process-wide configured pykakasi
converter. Reject empty normalized values at parser boundaries rather than
silently inventing values.

- [ ] **Step 4: Run and verify GREEN**

Run: `python -m unittest scripts.dictionary_import.tests.test_normalization -v`

Expected: PASS.

- [ ] **Step 5: Commit**

```text
git add scripts/dictionary_import/jlas_import scripts/dictionary_import/tests/test_normalization.py
git commit -m "feat: add dictionary import normalization"
```

### Task 3: JMdict Streaming Parser

**Files:**
- Create: `scripts/dictionary_import/jlas_import/parsers.py`
- Create: `scripts/dictionary_import/tests/test_jmdict_parser.py`
- Create: `scripts/dictionary_import/tests/fixtures/jmdict.xml`
- Create: `scripts/dictionary_import/tests/fixtures/jmdict-malformed-entry.xml`

**Interfaces:**
- Consumes: `VocabularyRecord` and child dataclasses from Task 2.
- Produces: `iter_jmdict(path) -> Iterator[VocabularyRecord]`.

- [ ] **Step 1: Add a representative JMdict fixture and failing tests**

```python
class JmDictParserTest(unittest.TestCase):
    def test_streams_variants_readings_senses_glosses_pos_and_all_domains(self):
        records = list(iter_jmdict(FIXTURES / "jmdict.xml"))
        school = next(row for row in records if row.external_id == "1000001")
        self.assertEqual("学校", school.expression)
        self.assertEqual("がっこう", school.reading)
        self.assertIn("學校", school.variants)
        self.assertEqual({"comp", "law", "med"}, set(school.senses[0].domains))
        self.assertGreaterEqual(len(school.senses[0].glosses), 2)
```

The fixture also contains multiple readings/senses/glosses, repeated Kanji,
two same-meaning words, and one same-expression/different-reading pair.

- [ ] **Step 2: Run and verify RED**

Run: `python -m unittest scripts.dictionary_import.tests.test_jmdict_parser -v`

Expected: FAIL because `iter_jmdict` is missing.

- [ ] **Step 3: Implement lxml iterparse with entity-code preservation**

Release each `<entry>` after yielding it. Capture entity node names for `pos`
and `field`, use English DTD descriptions as labels, select a compatible
primary reading, and serialize restrictions into usage notes plus warning
records. A missing `ent_seq`, expression, or reading raises a record validation
error carrying the best available identifier.

- [ ] **Step 4: Run and verify GREEN**

Run: `python -m unittest scripts.dictionary_import.tests.test_jmdict_parser -v`

Expected: PASS and prove iteration does not retain cleared prior elements.

- [ ] **Step 5: Commit**

```text
git add scripts/dictionary_import/jlas_import/parsers.py scripts/dictionary_import/tests/test_jmdict_parser.py scripts/dictionary_import/tests/fixtures
git commit -m "feat: stream and map JMdict records"
```

### Task 4: KANJIDIC2 and KanjiVG Parsers

**Files:**
- Modify: `scripts/dictionary_import/jlas_import/parsers.py`
- Create: `scripts/dictionary_import/tests/test_kanji_parsers.py`
- Create: `scripts/dictionary_import/tests/fixtures/kanjidic2.xml`
- Create: `scripts/dictionary_import/tests/fixtures/kanjivg/065e5.svg`
- Create: `scripts/dictionary_import/tests/fixtures/kanjivg/09999.svg`

**Interfaces:**
- Produces: `iter_kanjidic2(path) -> Iterator[KanjiRecord]` and
  `iter_kanjivg(path, uri_prefix) -> Iterator[KanjiSvgRecord]`.

- [ ] **Step 1: Write failing parser tests**

```python
def test_kanjidic2_keeps_joyo_and_separate_readings(self):
    records = list(iter_kanjidic2(FIXTURES / "kanjidic2.xml"))
    self.assertEqual(["日"], [row.literal for row in records])
    self.assertEqual({"ON", "KUN", "NANORI"}, {r.kind for r in records[0].readings})
    self.assertIsNone(records[0].jlpt_level)

def test_kanjivg_builds_stable_uri_from_codepoint(self):
    row = next(iter_kanjivg(FIXTURES / "kanjivg", "/kanjivg/kanji"))
    self.assertEqual("65E5", row.unicode_code)
    self.assertEqual("/kanjivg/kanji/065e5.svg", row.svg_uri)
```

- [ ] **Step 2: Run and verify RED**

Run: `python -m unittest scripts.dictionary_import.tests.test_kanji_parsers -v`

Expected: FAIL because both parser functions are missing.

- [ ] **Step 3: Implement both parsers**

Filter KANJIDIC2 on grades `{1,2,3,4,5,6,8}`, require literal/UCS, select the
first classical radical, keep each non-empty Japanese reading separately, and
ignore legacy JLPT. For KanjiVG, validate the five-digit hexadecimal filename
or root metadata and yield one stable URI per SVG.

- [ ] **Step 4: Run and verify GREEN**

Run: `python -m unittest scripts.dictionary_import.tests.test_kanji_parsers -v`

Expected: PASS.

- [ ] **Step 5: Commit**

```text
git add scripts/dictionary_import/jlas_import/parsers.py scripts/dictionary_import/tests/test_kanji_parsers.py scripts/dictionary_import/tests/fixtures
git commit -m "feat: parse KANJIDIC2 and KanjiVG"
```

### Task 5: Store and Import Pipeline

**Files:**
- Create: `scripts/dictionary_import/jlas_import/store.py`
- Create: `scripts/dictionary_import/jlas_import/pipeline.py`
- Create: `scripts/dictionary_import/tests/test_pipeline.py`

**Interfaces:**
- Produces: `MySqlDictionaryStore(connection)`,
  `ImportPipeline(store, report_writer, chunk_size)`, and
  `ImportSummary(total, inserted, updated, skipped, failed, status)`.
- Store methods include `require_active_source`, `find_completed_batch`,
  `start_batch`, `finish_batch`, `savepoint`, `commit_chunk`,
  `upsert_vocabulary`, `upsert_kanji`, and `update_kanji_svg`.

- [ ] **Step 1: Write failing behavior tests against a recording store**

```python
def test_identical_completed_checksum_creates_skipped_audit_batch(self):
    store = RecordingStore(completed=True)
    summary = ImportPipeline(store, MemoryReport(), 100).run(metadata, records=[])
    self.assertEqual("SKIPPED", summary.status)
    self.assertEqual(0, store.mutations)

def test_failed_record_rolls_back_only_its_savepoint(self):
    store = RecordingStore(fail_ids={"bad"})
    summary = ImportPipeline(store, MemoryReport(), 2).run(metadata, records())
    self.assertEqual("COMPLETED_WITH_ERRORS", summary.status)
    self.assertEqual((2, 0, 0, 1), summary.mutation_counters)
    self.assertEqual(["bad"], store.rolled_back_records)
```

- [ ] **Step 2: Run and verify RED**

Run: `python -m unittest scripts.dictionary_import.tests.test_pipeline -v`

Expected: import failure because pipeline/store modules do not exist.

- [ ] **Step 3: Implement the pipeline state machine**

Compute one action per source record, use savepoints, commit at chunk
boundaries, write sanitized structured failures, finalize exact counters, and
mark fatal errors FAILED. Dry-run follows the same validation/action planning
path but rolls back and never calls batch creation.

- [ ] **Step 4: Implement prepared MySQL persistence**

Implement source/POS/domain/Kanji caches; textbook-safe vocabulary merge;
external-reference lookup; existence-based upserts for variants, readings,
senses, glosses, POS/domain bridges, and code-point-position Kanji links;
KANJIDIC2 radical/Kanji/readings; and KanjiVG URI updates. Use the existing
unique keys and no `LIKE` scans.

- [ ] **Step 5: Run and verify GREEN**

Run: `python -m unittest scripts.dictionary_import.tests.test_pipeline -v`

Expected: PASS.

- [ ] **Step 6: Commit**

```text
git add scripts/dictionary_import/jlas_import/store.py scripts/dictionary_import/jlas_import/pipeline.py scripts/dictionary_import/tests/test_pipeline.py
git commit -m "feat: add idempotent dictionary import pipeline"
```

### Task 6: MySQL Integration Proof

**Files:**
- Create: `scripts/dictionary_import/tests/test_mysql_integration.py`
- Create: `scripts/dictionary_import/tests/mysql_test_schema.sql`

**Interfaces:**
- Consumes: parser, pipeline, store, schema, and fixtures from Tasks 1–5.
- Produces: opt-in end-to-end proof guarded by `JLAS_IMPORT_TEST_MYSQL=1` and a
  database name ending `_test`.

- [ ] **Step 1: Write the skipped-by-default integration tests**

```python
@unittest.skipUnless(os.getenv("JLAS_IMPORT_TEST_MYSQL") == "1", "MySQL opt-in required")
class MySqlImportIntegrationTest(unittest.TestCase):
    def test_second_identical_import_changes_no_domain_row_counts(self):
        first = self.import_fixture("jmdict")
        before = self.count_dictionary_rows()
        second = self.import_fixture("jmdict")
        after = self.count_dictionary_rows()
        self.assertEqual("COMPLETED", first.status)
        self.assertEqual("SKIPPED", second.status)
        self.assertEqual(before, after)
```

Additional methods assert textbook preservation, every fixture domain,
repeated Kanji positions, Kanji readings/SVG, batch counters, and orphan-free
foreign keys.

- [ ] **Step 2: Run without opt-in and verify safe skip**

Run: `python -m unittest scripts.dictionary_import.tests.test_mysql_integration -v`

Expected: SKIPPED with the explicit opt-in reason.

- [ ] **Step 3: Add compact schema/setup and guarded cleanup**

Refuse any database whose name does not end `_test`. Create only fixture-owned
rows and delete only rows tied to the test source codes during teardown.

- [ ] **Step 4: Run with MySQL when available**

Run: `$env:JLAS_IMPORT_TEST_MYSQL='1'; python -m unittest scripts.dictionary_import.tests.test_mysql_integration -v`

Expected: PASS on a configured MySQL 8 test database; otherwise record the
exact environmental blocker in the final report.

- [ ] **Step 5: Commit**

```text
git add scripts/dictionary_import/tests/test_mysql_integration.py scripts/dictionary_import/tests/mysql_test_schema.sql
git commit -m "test: cover dictionary import idempotency"
```

### Task 7: CLI, Dependencies, and Operator Documentation

**Files:**
- Create: `scripts/dictionary_import/import_data.py`
- Create: `scripts/dictionary_import/jlas_import/cli.py`
- Create: `scripts/dictionary_import/requirements.txt`
- Create: `scripts/dictionary_import/tests/test_cli.py`
- Create: `docs/dictionary-import.md`
- Modify: `.gitignore` only if generated report/cache patterns are not covered.

**Interfaces:**
- Produces the documented command `python scripts/dictionary_import/import_data.py --source SOURCE --file PATH`.

- [ ] **Step 1: Write failing CLI tests**

```python
def test_cli_requires_existing_readable_input(self):
    result = run_cli("--source", "jmdict", "--file", "missing.xml")
    self.assertEqual(2, result.exit_code)
    self.assertIn("input file does not exist", result.stderr)

def test_cli_never_echoes_password_on_connection_error(self):
    result = run_cli_with_env(DB_PASSWORD="never-print-this")
    self.assertNotIn("never-print-this", result.stderr + result.stdout)
```

- [ ] **Step 2: Run and verify RED**

Run: `python -m unittest scripts.dictionary_import.tests.test_cli -v`

Expected: FAIL because the CLI does not exist.

- [ ] **Step 3: Implement CLI and dependency file**

Validate source/file/version/chunk size, load environment variables, connect
through mysql-connector-python, route to the selected parser, print a concise
summary, and return non-zero only for validation/fatal failures. The entry
script only calls `cli.main()` under `if __name__ == "__main__"`.

- [ ] **Step 4: Write operator documentation**

Document official downloads/licenses/attribution, virtual environment setup,
environment variables, schema and migration commands, all source commands,
KanjiVG URI prefix, dry-run, summary/status meanings, rerun rules, JSONL errors,
test commands, and the absence of startup/runtime external imports.

- [ ] **Step 5: Run and verify GREEN**

Run: `python -m unittest scripts.dictionary_import.tests.test_cli -v`

Expected: PASS.

- [ ] **Step 6: Commit**

```text
git add scripts/dictionary_import/import_data.py scripts/dictionary_import/jlas_import/cli.py scripts/dictionary_import/requirements.txt scripts/dictionary_import/tests/test_cli.py docs/dictionary-import.md .gitignore
git commit -m "docs: add dictionary import command guide"
```

### Task 8: Full Verification and Scope Audit

**Files:**
- Modify only files already listed if verification reveals task-scoped defects.

**Interfaces:**
- Consumes all deliverables.
- Produces the final 12-section Jira handoff report.

- [ ] **Step 1: Run all Python tests**

Run: `python -m unittest discover -s scripts/dictionary_import/tests -v`

Expected: all unit/contract tests PASS; MySQL tests explicitly SKIP unless
opted in.

- [ ] **Step 2: Run Maven verification**

Run: `mvn test`

Expected: BUILD SUCCESS.

- [ ] **Step 3: Run importer help and dry-run validation**

Run: `python scripts/dictionary_import/import_data.py --help`

Expected: exit 0 and list all three sources and common options.

- [ ] **Step 4: Run MySQL fixture import twice when a test database exists**

Verify row counts, textbook merge, all domains, counters, and orphan queries.
If MySQL is unavailable, preserve the opt-in automated test and report the exact
missing prerequisite.

- [ ] **Step 5: Audit diff and secrets**

Run: `git diff --check`, `git status --short`, and targeted searches for
credential-shaped values in newly added files. Confirm no search/API/UI or
Spring startup file changed.

- [ ] **Step 6: Commit verification-only fixes, if any**

```text
git add -- jlas_schema.sql sql/migrations/004_dictionary_import_metadata.sql scripts/dictionary_import docs/dictionary-import.md
git commit -m "fix: complete dictionary import verification"
```
