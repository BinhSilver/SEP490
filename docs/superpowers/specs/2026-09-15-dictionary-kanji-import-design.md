# Dictionary and Kanji Base Data Import Design

## Purpose

Implement JLAS2-19 as a one-time, operator-invoked Python import pipeline for
JMdict, KANJIDIC2, and KanjiVG. The pipeline populates the existing dictionary
and Kanji tables, adds import provenance and batch observability, and remains
safe to rerun without starting from Spring Boot or calling external sources at
runtime.

## Scope

The implementation will:

- add `dictionary_source`, `dictionary_import_batch`, and
  `vocabulary_external_reference` to the canonical schema and an additive SQL
  migration;
- parse source datasets incrementally instead of loading complete XML files;
- normalize lookup and deduplication keys without changing display text;
- insert or update the existing Dictionary/Kanji model and its relationships;
- merge dictionary data into existing textbook vocabulary entries;
- record batch counters, statuses, fatal errors, warnings, and record failures;
- provide dry-run support, small fixtures, automated tests, and operator docs.

The implementation will not add search APIs, UI, autocomplete, ranking,
handwriting history, schedulers, AI translation, external runtime calls,
Elasticsearch, Redis, vector storage, or source-specific vocabulary tables.

## Existing Architecture and Constraints

`jlas_schema.sql` is the canonical MySQL schema. Hibernate is configured with
`ddl-auto: validate`, so the application does not create or migrate these
tables. The repository also keeps additive SQL scripts under `sql/migrations/`.

The existing tables named by the Jira task already exist and remain canonical:
`radical`, `kanji`, `kanji_reading`, `vocabulary_entry`,
`vocabulary_reading`, `vocabulary_variant`, `vocabulary_kanji`,
`vocabulary_sense`, `vocabulary_gloss`, `dictionary_part_of_speech`,
`sense_part_of_speech`, `dictionary_domain`, `sense_domain`,
`content_vocabulary`, `dictionary_search_history`, and `dictionary_favorite`.
No replacement entities or tables will be introduced.

The current branch contains no tracked `src/test` tree and has no established
Testcontainers setup. Python tests will therefore cover the importer directly,
with a MySQL integration suite enabled when a test database is provided.

## Chosen Architecture

Use a Python package at `scripts/dictionary_import` with a single command-line
entry point:

```text
import_data.py
  -> argument and environment validation
  -> SHA-256 checksum
  -> source-specific streaming parser
  -> shared normalized source records
  -> source-specific importer service
  -> prepared MySQL statements and lookup caches
  -> import batch summary and JSONL error report
```

Python is isolated from the Spring Boot runtime and is a good fit for large XML
streaming. A direct MySQL writer also allows explicit chunk boundaries,
savepoints, counters, and dry-run rollback without creating Java application
startup hooks.

The CLI supports these source names:

- `jmdict`
- `kanjidic2`
- `kanjivg`

Common arguments include `--file`, `--source-version`, `--chunk-size`,
`--dry-run`, and `--report-file`. KanjiVG accepts either one SVG file or a
directory tree and also accepts a stable URI prefix used for
`kanji.stroke_svg_uri`.

Database configuration is read only from `DB_HOST`, `DB_PORT`, `DB_NAME`,
`DB_USERNAME`, and `DB_PASSWORD`. Credentials are never printed or placed in
reports.

## Schema Design

### `dictionary_source`

Contains the requested columns, a unique `code`, required `name`, nullable
source/license URLs, and `is_active` defaulting to true. The canonical schema
seeds active records for JMDICT, KANJIDIC2, and KANJIVG because those importers
are delivered by this task. New sources can be inserted as rows without schema
changes.

### `dictionary_import_batch`

References `dictionary_source` and records version, SHA-256 checksum, status,
timestamps, non-negative counters, and a sanitized fatal error summary. Status
is constrained to `RUNNING`, `COMPLETED`, `COMPLETED_WITH_ERRORS`, `FAILED`, or
`SKIPPED`. A lookup index covers source, version, checksum, and status, but no
unique constraint prevents retaining multiple attempts.

### `vocabulary_external_reference`

References `vocabulary_entry` and `dictionary_source`. The unique key on
`(source_id, external_entry_id)` makes the stable source identifier the
authoritative idempotency key. JMdict stores `ent_seq` here. A vocabulary entry
may have references from multiple sources.

The additive migration creates only these three tables and seeds the three
supported sources. Its rollback is documented as dropping the tables in child
to parent order. Rollback is intentionally manual and destructive because it
removes provenance history; the importer never invokes it.

## Shared Record Model and Normalization

Parsers yield small source-neutral records one at a time. They do not own SQL
or transactions. The normalizer applies:

- Unicode NFKC;
- leading/trailing whitespace removal;
- consecutive whitespace collapse;
- Katakana-to-Hiragana conversion for normalized reading keys;
- Hepburn-style romaji generation for reading columns.

Original expression and reading values are preserved for display. Only
`normalized_expression`, `normalized_reading`, and `reading_romaji` receive
derived values. Vietnamese meanings are never used as identity keys.

## JMdict Mapping

For each `<entry>`:

- `ent_seq` becomes the external reference;
- the first written form becomes the primary expression;
- the first reading compatible with that expression becomes the primary
  reading;
- remaining written forms become `vocabulary_variant` rows;
- remaining readings become `vocabulary_reading` rows;
- senses, every gloss language, parts of speech, and fields become the existing
  sense/gloss/catalog/bridge rows;
- characters in the primary expression create `vocabulary_kanji` rows only
  when the character exists in `kanji`.

JMdict entity names are retained as stable POS/domain codes while their source
descriptions provide English display names. Catalog rows are created
dynamically for every encountered value. Because `name_vi` is non-null in the
existing schema, an untranslated item uses its English name, or its code when
no English label exists, and emits a warning. No invented Vietnamese
translation is stored.

Reading restrictions and sense restrictions cannot be represented as typed
relationships by the current schema. Their source values are retained in the
available usage-note text and a structured warning records that the mapping is
not lossless. No restriction tables are added.

## Textbook Merge and Vocabulary Idempotency

The normalized expression/reading pair is the shared vocabulary identity.

- A new JMdict word is created with `usage_scope = DICTIONARY`.
- A matching `TEXTBOOK` word is reused and changed to `BOTH`.
- Its `meaning`, `word_type`, examples, JLPT level, and existing
  `content_vocabulary` links are not overwritten.
- A matching `DICTIONARY` or `BOTH` word is reused.

The external reference is created or updated against that shared row. Child
collections are upserted by existing schema keys and explicit existence checks.
The importer is additive when a changed source removes a child value because
the existing sense/gloss tables do not carry per-source provenance; deleting
such rows could remove J-LAS-curated data. This limitation is documented.

When source, version, and checksum already have a successful `COMPLETED` batch,
the importer creates a new `SKIPPED` batch with zero mutation counters. This
provides an audit trail while guaranteeing a byte-identical rerun does not
change dictionary rows.

## KANJIDIC2 Mapping

The parser selects Jōyō entries using KANJIDIC2 grade values 1 through 6 and
8. It maps:

- literal to `kanji.character`;
- UCS value to an uppercase hexadecimal `kanji.unicode_code` without a prefix;
- stroke count to `kanji.stroke_count`;
- the classical radical number to the primary `radical` relation;
- `ja_on`, `ja_kun`, and `nanori` values to separate ON, KUN, and NANORI rows.

The required radical character is derived from the sequential Unicode Kangxi
Radicals block for numbers 1 through 214. A Kanji has one primary classical
radical. Empty readings are rejected. Legacy KANJIDIC2 JLPT metadata is ignored
because it does not represent the current JLPT system.

## KanjiVG Mapping

Each SVG is processed independently. Its Kanji is identified by a hexadecimal
Unicode code point from KanjiVG metadata or the standard filename. A matching
`kanji` row receives a stable configured URI/path. SVG bodies and user drawing
data are never stored in MySQL. Missing Kanji rows are skipped with a warning.

## Transactions, Counters, and Failures

The importer validates the active source row and input file before creating a
`RUNNING` batch. It computes the checksum incrementally.

Normal imports use configurable chunks. Each record executes behind a
savepoint, so a record failure rolls back every partial row for that record and
increments `failed_records`. Successful records increment exactly one of
`inserted_records`, `updated_records`, or `skipped_records`. Completed chunks
are committed; the whole dataset is never held in one transaction.

Recoverable record failures produce `COMPLETED_WITH_ERRORS`. An unreadable
file, malformed stream that prevents continuation, database connection loss,
or other fatal pipeline error produces `FAILED`. Fatal `error_message` values
are sanitized, bounded summaries without stack traces or secrets.

The JSONL report includes batch ID, source code, record identifier when known,
severity, stable warning/error code, and sanitized message. Dry-run performs
parsing, validation, and planned-action counting in a transaction that is
rolled back; it does not create an import batch or modify persistent data.

## Performance

- XML/SVG processing is iterative and releases parsed elements promptly.
- SQL uses parameters, not string interpolation.
- Source, POS, domain, vocabulary identity, and Kanji lookups are cached for
  the current process.
- Chunk size is configurable with a conservative default.
- Foreign-key checks remain enabled.

## Automated Tests

Tests follow red-green-refactor and use small committed fixtures. Unit and
contract tests cover normalization, entity-code extraction, streaming behavior,
validation, mapping, counters, sanitization, and rollback decisions without a
database server.

An opt-in MySQL integration suite creates isolated tables from a compact test
schema in a database named by test-only environment variables. It covers:

- all requested JMdict shapes, dynamic multi-domain data, distinct words with
  equal meanings, and equal expressions with different readings;
- KANJIDIC2 radicals/readings and KanjiVG links/missing matches;
- first import and exact second-import row-count stability;
- textbook merge and preservation of content links/summary fields;
- repeated Kanji positions and foreign-key integrity;
- malformed records, mid-import record failure, final status, and counters.

The suite never drops or truncates an operator database. It requires an
explicit test database opt-in and uses uniquely prefixed test data with cleanup
limited to those records.

## Documentation and Verification

`docs/dictionary-import.md` will document official sources and attribution,
dataset acquisition, Python setup, environment variables, schema/migration
application, each importer command, dry-run, summaries, retry behavior, error
reports, and test commands. It will explicitly state that no importer runs at
Spring Boot startup and runtime dictionary reads use MySQL only.

Completion verification includes Python tests, Maven build, fixture import on
an available empty MySQL database, identical-fixture rerun and row-count
comparison, textbook merge, domains, counters, foreign keys, and final Git
diff review. Any unavailable MySQL verification is reported precisely rather
than represented as passed.

## Planned File Structure

```text
jlas_schema.sql
sql/migrations/004_dictionary_import_metadata.sql
scripts/dictionary_import/import_data.py
scripts/dictionary_import/requirements.txt
scripts/dictionary_import/jlas_import/__init__.py
scripts/dictionary_import/jlas_import/cli.py
scripts/dictionary_import/jlas_import/models.py
scripts/dictionary_import/jlas_import/normalization.py
scripts/dictionary_import/jlas_import/parsers.py
scripts/dictionary_import/jlas_import/store.py
scripts/dictionary_import/jlas_import/pipeline.py
scripts/dictionary_import/tests/test_*.py
scripts/dictionary_import/tests/fixtures/**
docs/dictionary-import.md
```

Existing Java Dictionary entities, repositories, controllers, services, and
search behavior are not modified by this task.
