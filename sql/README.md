# SQL and database artifact map

Use this folder as the canonical home for hand-maintained SQL:

- `migrations/` — incremental schema changes. Apply only migrations required by
  the checked-out code and target database version; rollback scripts are explicit
  companions, not part of the forward run order.
- `seeds/` — data loads. `seed_data_jlas.sql` is generated from
  `data/seed_jlas/` by `tools/generate_seed.py`; edit its sources/generator, then
  regenerate. `seed_data_vnjpclub.sql` belongs to the digitization import and is
  deliberately run last. Files in `_cu/`, when present, are archived and must not
  be used.
- `audits/` — read-only checks; do not run these as migrations or seeds.
  `audits/learning-readiness.sql` combines the task-owned content, dialogue
  audio and Smart Test readiness queries. The full release-gate audit remains
  separate because the release-gate validator loads it by path.
  It also holds the dated local JLPT/N4 source diagnostics (formerly
  `learning-jlpt-local-source-checks.sql`) and the Learning content fingerprint
  used to compare a database before/after a seed (formerly `learning-fingerprint.sql`).

## Fresh local database

Follow the repository's `AGENTS.md` run order: `jlas_schema.sql`, any applicable
older-schema migrations (018/019/020 only when needed), then the generated core
seed, task/team-specific seeds, dev-only demo seed if needed, and the vnjpclub
seed last, then the JLPT exams (`jlpt_full_setup.sql`, then migrations `053` → `041` → `054`;
`041` creates the `jlpt_attempt_history` table that `054` writes to), then the Learning flow
schema and data (below), followed LAST by the data
migrations `085` → `086` → `087` → `088` (they update rows the seeds and the learning data
insert; they find vocabulary by its normalized key and learning-data questions by
`source_code`, never by DB-specific ids). Re-run `085` → `086` → `088` after re-running any
seed or learning data file. Inspect each file's header before execution. Never use a force mode
that continues after SQL errors.

## Existing database

Back up the exact database and verify the backup before writes. Prefer a narrowly
scoped, rerunnable backfill over a full seed.

## Learning flow (`learning/`)

The Learning flow (Smart Test survey, learning path, goal curriculum, jpath) keeps
one schema file, `learning/learning_schema.sql` (every Learning table, column, index,
FK and CHECK; re-runnable, upgrades older Learning tables in place), and six data files:
`learning/learning_data_N5.sql` (common data, then N5), `_N4`, `_N3`, `_N2`, `_N1` and
`_comm` (communication path); all re-runnable and stored with Git LFS (run `git lfs pull`
after pulling). Run the schema, then the data files in that order, after the normal
base seeds and migrations and before the data migrations `085` → `086` → `087` → `088`
(run those again after reloading a learning data file). They replace the former Learning
migrations 004-015, 022 and seeds `learning_owned.sql` /
`learning_owned_part2.sql`. New Learning tables and data go into these files, not
into `migrations/` or `seeds/`. See `learning/README.md`; verify with
`node tools/learning-data-extract/verify_learning_sql.mjs`.

The Learning practice/source holding tables and their seeds are included in
the canonical SQL. Holding rows do not automatically become learner-visible
lessons. The sole task-owned data input is
`data/learning-owned/learning-content.json`; its availability states distinguish
source snapshots from runtime-ready paths.

## Special-purpose SQL outside this folder

- Root `jlas_schema.sql` is the canonical schema. It is intentionally not copied
  here because the repository's database instructions require it at the root.
- Learning test-only fixtures live under `src/test/resources/fixtures/learning/`.
  They must not enter the normal seed chain.
- One-time local demo account builders/seeds are intentionally not part of the
  project. Use tracked E2E fixtures only for automated tests; never add demo
  credentials or synthetic learner progress to a shared seed.
- `src/main/resources/db/migrations/jlpt_full_setup.sql` is a generated,
  destructive JLPT reset/import entry point. It is kept at its current path
  because the release-gate test and generated manifest reference it. Use only
  on a fresh/approved database after a verified backup; it is not a routine
  migration and must not be folded into the learning-path seed chain.
- `scripts/dictionary_import/tests/mysql_test_schema.sql` is a test fixture.

Generated drafts and review packets live under `outputs/` and are catalogued
in `outputs/README.md`; they are not part of the normal web seed chain. Local
recovery backups are ignored by Git and must be managed separately from seeds.
